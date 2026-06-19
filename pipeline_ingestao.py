#!/usr/bin/env python3
"""
Pipeline de Ingestão FHIR com Auto-Trigger
-------------------------------------------
✓ PUT idempotente (sem duplicados)
✓ Bundle transaction (1 chamada = N recursos)
✓ ThreadPoolExecutor paralelo
✓ Retry automático com backoff
✓ Auto-trigger (monitora data/input continuamente)

Nota: Validação FHIR é feita em fhir.py (build_resources)
"""

import json
import logging
import os
import shutil
import sys
import time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# Conversor FHIR
from fhir import build_resources

# ==========================================================
# CONFIGURAÇÃO
# ==========================================================
BASE_DIR      = os.path.dirname(os.path.abspath(__file__))
INPUT_DIR     = os.path.join(BASE_DIR, "data", "input")
PROCESSED_DIR = os.path.join(BASE_DIR, "data", "processed")
ERROR_DIR     = os.path.join(BASE_DIR, "data", "error")
LOG_DIR       = os.path.join(BASE_DIR, "logs")
LOG_FILE      = os.path.join(LOG_DIR, "ingesta.log")

HAPI_URL      = os.getenv("HAPI_URL", "http://localhost:8080/fhir")
MAX_WORKERS   = int(os.getenv("PIPELINE_WORKERS", "8"))
REQUEST_TIMEOUT = int(os.getenv("PIPELINE_TIMEOUT", "30"))
POLL_INTERVAL = int(os.getenv("PIPELINE_POLL", "5"))  # segundos entre verificações

FHIR_HEADERS  = {
    "Content-Type": "application/fhir+json",
    "Accept":       "application/fhir+json",
}

# ==========================================================
# LOGGING — stdout + ficheiro
# ==========================================================
def _setup_logging() -> logging.Logger:
    os.makedirs(LOG_DIR, exist_ok=True)
    logger = logging.getLogger("pipeline")
    logger.setLevel(logging.INFO)

    fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")

    fh = logging.FileHandler(LOG_FILE, encoding="utf-8")
    fh.setFormatter(fmt)

    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)

    logger.addHandler(fh)
    logger.addHandler(sh)
    return logger

log = _setup_logging()


# ==========================================================
# SESSÃO HTTP com retry automático
# ==========================================================
def _build_session() -> requests.Session:
    """
    Cria uma sessão com retry automático para erros de rede
    e respostas 5xx / 429.  Backoff: 1s, 2s, 4s.
    """
    session = requests.Session()
    retry = Retry(
        total=3,
        backoff_factor=1,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=["POST", "PUT"],
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session

_SESSION = _build_session()


# ==========================================================
# AUTO-TRIGGER: Monitora data/input continuamente
# ==========================================================
class FilePoller:
    """
    Poller simples sem dependências externas.
    Monitora data/input/ a cada POLL_INTERVAL segundos.
    """
    
    def __init__(self, input_dir: str, poll_interval: int = 5):
        self.input_dir = Path(input_dir)
        self.poll_interval = poll_interval
        self.processed_files = set()
    
    def scan_for_new_files(self) -> List[Path]:
        """Retorna lista de .json novos na pasta"""
        if not self.input_dir.exists():
            self.input_dir.mkdir(parents=True, exist_ok=True)
            return []
        
        new_files = []
        for file in self.input_dir.glob("*.json"):
            if file.name not in self.processed_files:
                new_files.append(file)
                self.processed_files.add(file.name)
        
        return new_files
    
    def start_watching(self, callback):
        """
        Loop de monitorização contínua.
        Chama callback(filepath) para cada novo arquivo.
        """
        log.info(f"🔔 Iniciando auto-trigger em: {self.input_dir}")
        log.info(f"   Poll interval: {self.poll_interval}s")
        
        try:
            while True:
                new_files = self.scan_for_new_files()
                
                if new_files:
                    log.info(f"📂 Detectados {len(new_files)} novo(s) arquivo(s)")
                    for filepath in new_files:
                        log.info(f"   ➜ {filepath.name}")
                        try:
                            callback(str(filepath))
                        except Exception as e:
                            log.error(f"   ✗ Erro: {e}")
                
                time.sleep(self.poll_interval)
                
        except KeyboardInterrupt:
            log.info("🛑 Auto-trigger interrompido pelo utilizador")



# ==========================================================
# SETUP DE DIRECTÓRIOS
# ==========================================================
def setup_directories() -> None:
    for d in [INPUT_DIR, PROCESSED_DIR, ERROR_DIR, LOG_DIR]:
        os.makedirs(d, exist_ok=True)


# ==========================================================
# NORMALIZAÇÃO DA LISTA DE RECURSOS
# ==========================================================
def normalize(resources: Any) -> List[Dict[str, Any]]:
    """
    Aceita a saída de build_resources independentemente do formato:
    lista de recursos, Bundle com 'entry', ou recurso singular.
    """
    if isinstance(resources, list):
        # desempacotar entradas do tipo {"resource": {...}, "request": {...}}
        result = []
        for item in resources:
            if isinstance(item, dict) and "resource" in item:
                result.append(item["resource"])
            elif isinstance(item, dict) and "resourceType" in item:
                result.append(item)
        return result

    if isinstance(resources, dict):
        if "entry" in resources:
            return normalize(resources["entry"])
        if "resourceType" in resources:
            return [resources]

    return []


# ==========================================================
# CONSTRUÇÃO DO BUNDLE TRANSACTION (1 chamada = todos recursos)
# ==========================================================
def build_bundle(resources: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Empacota todos os recursos num único Bundle do tipo 'transaction'.
    Usa PUT para cada recurso que tenha ID (idempotente — RF03).
    Usa POST como fallback para recursos sem ID.
    """
    entries = []
    for resource in resources:
        r_type = resource.get("resourceType")
        r_id   = resource.get("id")

        if not r_type:
            continue

        if r_id:
            # PUT idempotente: cria ou substitui o recurso com este ID
            entry = {
                "fullUrl": f"{r_type}/{r_id}",
                "resource": resource,
                "request": {
                    "method": "PUT",
                    "url":    f"{r_type}/{r_id}",
                },
            }
        else:
            # POST como fallback (recursos sem ID definido)
            entry = {
                "resource": resource,
                "request": {
                    "method": "POST",
                    "url":    r_type,
                },
            }

        entries.append(entry)

    return {
        "resourceType": "Bundle",
        "type":         "transaction",
        "entry":        entries,
    }


# ==========================================================
# ENVIO DO BUNDLE PARA O HAPI
# ==========================================================
def send_bundle(bundle: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """
    Envia o Bundle transaction para o HAPI FHIR.
    Devolve o JSON de resposta ou None em caso de erro.
    """
    try:
        response = _SESSION.post(
            HAPI_URL,
            json=bundle,
            headers=FHIR_HEADERS,
            timeout=REQUEST_TIMEOUT,
        )
    except requests.exceptions.RequestException as exc:
        log.error(f"Falha de ligação ao HAPI: {exc}")
        return None

    if response.status_code not in (200, 201):
        log.error(
            f"HAPI devolveu HTTP {response.status_code}: "
            f"{response.text[:500]}"
        )
        return None

    return response.json()


# ==========================================================
# PROCESSAMENTO DE UM FICHEIRO JSON
# ==========================================================
def ingestar_ficheiro(json_file_path: str, filename: str) -> bool:
    """
    Processa um ficheiro JSON:
      1. Lê e converte para recursos FHIR
      2. Constrói um Bundle transaction com PUT idempotente
      3. Envia o Bundle ao HAPI numa única chamada HTTP
      4. Move o ficheiro para processed/ ou error/ consoante resultado

    Devolve True em caso de sucesso, False em caso de erro.
    """
    log.info(f"A processar: {filename}")

    # --- 1. Leitura do ficheiro ---
    try:
        with open(json_file_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError) as exc:
        log.error(f"{filename} — erro de leitura/parse: {exc}")
        _mover(json_file_path, ERROR_DIR, filename)
        return False

    # --- 2. Conversão para FHIR (inclui validação interna) ---
    try:
        fhir_output  = build_resources(data)
        resources    = normalize(fhir_output)
    except Exception as exc:
        log.error(f"{filename} — erro na conversão FHIR: {exc}")
        _mover(json_file_path, ERROR_DIR, filename)
        return False

    if not resources:
        log.warning(f"{filename} — nenhum recurso gerado; movido para error/")
        _mover(json_file_path, ERROR_DIR, filename)
        return False

    # Validação mínima: deve existir pelo menos um Patient
    has_patient = any(r.get("resourceType") == "Patient" for r in resources)
    if not has_patient:
        log.error(f"{filename} — recurso Patient não encontrado; movido para error/")
        _mover(json_file_path, ERROR_DIR, filename)
        return False

    log.info(
        f"{filename} — {len(resources)} recursos gerados: "
        + ", ".join(r.get('resourceType', '?') for r in resources)
    )

    # --- 3. Bundle transaction (1 chamada HTTP) ---
    bundle   = build_bundle(resources)
    response = send_bundle(bundle)

    if not response:
        _mover(json_file_path, ERROR_DIR, filename)
        return False

    # --- 4. Mover para processed/ ---
    _mover(json_file_path, PROCESSED_DIR, filename)
    log.info(f"{filename} — ingestão concluída com sucesso ✓")
    return True


# ==========================================================
# UTILITÁRIO: mover ficheiro com segurança
# ==========================================================
def _mover(src: str, dest_dir: str, filename: str) -> None:
    dest = os.path.join(dest_dir, filename)
    # se já existir um ficheiro com o mesmo nome no destino, não falha
    if os.path.exists(dest):
        base, ext = os.path.splitext(filename)
        import time
        dest = os.path.join(dest_dir, f"{base}_{int(time.time())}{ext}")
    try:
        shutil.move(src, dest)
    except OSError as exc:
        log.error(f"Não foi possível mover {filename} para {dest_dir}: {exc}")


# ==========================================================
# PONTO DE ENTRADA PRINCIPAL
# ==========================================================
def process_files(file_list: List[str]) -> Tuple[int, int]:
    """
    Processa lista de ficheiros em paralelo.
    Retorna (sucesso, erros)
    """
    sucesso = 0
    erros = 0
    
    if not file_list:
        log.info("Nenhum ficheiro para processar")
        return 0, 0
    
    log.info(f"Iniciando processamento de {len(file_list)} ficheiro(s)")
    
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(
                ingestar_ficheiro,
                os.path.join(INPUT_DIR, filename),
                filename,
            ): filename
            for filename in file_list
        }

        for future in as_completed(futures):
            filename = futures[future]
            try:
                ok = future.result()
                if ok:
                    sucesso += 1
                else:
                    erros += 1
            except Exception as exc:
                log.error(f"Erro inesperado em {filename}: {exc}")
                erros += 1
    
    return sucesso, erros


def run_once():
    """Processa ficheiros que existem em data/input/ neste momento"""
    setup_directories()
    
    files = [f for f in os.listdir(INPUT_DIR) if f.endswith(".json")]
    if not files:
        log.info("Nenhum ficheiro JSON em data/input/")
        return
    
    sucesso, erros = process_files(files)
    log.info(f"Pipeline concluída — ✓ {sucesso} sucesso(s)  ✗ {erros} erro(s)  de {len(files)} ficheiro(s)")


def run_with_autotrigger():
    """Auto-trigger contínuo: monitora data/input/ e processa automaticamente"""
    setup_directories()
    
    poller = FilePoller(INPUT_DIR, poll_interval=POLL_INTERVAL)
    
    def on_new_file(filepath: str):
        """Callback quando novo arquivo é detectado"""
        filename = os.path.basename(filepath)
        sucesso, erros = process_files([filename])
        if sucesso > 0:
            log.info(f"✓ Auto-trigger sucesso: {filename}")
        else:
            log.error(f"✗ Auto-trigger erro: {filename}")
    
    poller.start_watching(on_new_file)


if __name__ == "__main__":
    # Suportar --watch para auto-trigger
    if len(sys.argv) > 1 and sys.argv[1] == "--watch":
        log.info("=" * 60)
        log.info("MODO AUTO-TRIGGER ATIVADO")
        log.info("Monitorando data/input/ continuamente...")
        log.info("Pressione Ctrl+C para interromper")
        log.info("=" * 60)
        run_with_autotrigger()
    else:
        run_once()