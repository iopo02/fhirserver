#!/usr/bin/env python3
"""
Pipeline de Ingestão FHIR com Auto-Trigger
-------------------------------------------
✓ PUT idempotente (sem duplicados)
✓ Bundle transaction (1 chamada = N recursos)
✓ ThreadPoolExecutor paralelo
✓ Retry automático com backoff
✓ Auto-trigger (monitora data/input continuamente)
✓ Autenticação JWT com service account

Nota: Validação FHIR é feita em fhir.py (build_resources)
"""

import json
import logging
import os
import shutil
import sys
import time
import fcntl
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Optional, Tuple

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# Conversor FHIR
from fhir import build_resources

# Autenticação JWT
from pipeline.auth import JwtAuthManager

# ==========================================================
# CONFIGURAÇÃO
# ==========================================================
BASE_DIR      = os.path.dirname(os.path.abspath(__file__))
INPUT_DIR     = os.path.join(BASE_DIR, "data", "input")
PROCESSED_DIR = os.path.join(BASE_DIR, "data", "processed")
ERROR_DIR     = os.path.join(BASE_DIR, "data", "error")
LOG_DIR       = os.path.join(BASE_DIR, "logs")
LOG_FILE      = os.path.join(LOG_DIR, "ingesta.log")

# FHIR Server
HAPI_URL           = os.getenv("HAPI_URL", "http://localhost:8080")
HAPI_FHIR_ENDPOINT = f"{HAPI_URL.rstrip('/')}/fhir"
MAX_WORKERS        = int(os.getenv("PIPELINE_WORKERS", "15"))
REQUEST_TIMEOUT    = int(os.getenv("PIPELINE_TIMEOUT", "30"))
POLL_INTERVAL      = int(os.getenv("PIPELINE_POLL", "5"))  # segundos entre verificações

# Autenticação (JWT Service Account)
AUTH_ENABLED       = os.getenv("PIPELINE_AUTH_ENABLED", "true").lower() == "true"
AUTH_EMAIL         = os.getenv("PIPELINE_AUTH_EMAIL", "admin@email.com")
AUTH_PASSWORD      = os.getenv("PIPELINE_AUTH_PASSWORD", "admin123")
AUTH_CACHE_FILE    = os.path.join(LOG_DIR, ".pipeline_auth_cache.json")

FHIR_HEADERS  = {
    "Content-Type": "application/fhir+json",
    "Accept":       "application/fhir+json",
}

# Instância global de autenticação (inicializada mais adiante)
auth_manager: Optional[JwtAuthManager] = None

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
        Loop de monitorização contínua com agregação.
        Acumula ficheiros durante POLL_INTERVAL e processa tudo de uma vez.
        Isto permite ao ThreadPoolExecutor trabalhar com lotes.
        """
        log.info(f"🔔 Iniciando auto-trigger em: {self.input_dir}")
        log.info(f"   Poll interval: {self.poll_interval}s (agregação)")
        log.info(f"   Ficheiros serão agregados em lotes antes do processamento")
        
        try:
            while True:
                new_files = self.scan_for_new_files()
                
                if new_files:
                    filenames = [f.name for f in new_files]
                    log.info(f"📂 Detectados {len(filenames)} novo(s) arquivo(s)")
                    for name in filenames:
                        log.info(f"   ➜ {name}")
                    
                    try:
                        log.info(f"⚙️  Processando {len(filenames)} ficheiro(s) em paralelo...")
                        callback(filenames)  # Passa todos de uma vez!
                    except Exception as e:
                        log.error(f"   ✗ Erro ao processar lote: {e}")
                
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
    
    Se AUTH_ENABLED, adiciona token JWT ao header Authorization.
    Devolve o JSON de resposta ou None em caso de erro.
    """
    try:
        headers = FHIR_HEADERS.copy()
        
        # Adicionar autenticação se ativada
        if AUTH_ENABLED and auth_manager:
            auth_headers = auth_manager.get_authorization_header()
            if not auth_headers:
                log.error("Falha ao obter token JWT; ingestão cancelada")
                return None
            headers.update(auth_headers)
        
        response = _SESSION.post(
            HAPI_FHIR_ENDPOINT,
            json=bundle,
            headers=headers,
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
# FILE LOCKING: Evitar race conditions entre workers
# ==========================================================
def _lock_file(file_path: str) -> Optional[int]:
    """
    Tenta adquirir lock exclusivo no ficheiro.
    Retorna o file descriptor se sucesso, None se falha.
    
    Isto garante que apenas um worker processa cada ficheiro.
    """
    try:
        fd = os.open(file_path, os.O_RDONLY)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)  # Non-blocking
            return fd
        except BlockingIOError:
            os.close(fd)
            return None
    except OSError:
        return None


def _unlock_file(fd: int) -> None:
    """Liberta o lock e fecha o file descriptor"""
    try:
        fcntl.flock(fd, fcntl.LOCK_UN)
    except OSError:
        pass
    finally:
        try:
            os.close(fd)
        except OSError:
            pass


# ==========================================================
# PROCESSAMENTO DE UM FICHEIRO JSON
# ==========================================================
def ingestar_ficheiro(json_file_path: str, filename: str) -> bool:
    """
    Processa um ficheiro JSON:
      1. Tenta adquirir lock exclusivo (evita race conditions)
      2. Detecta se o ficheiro já está em formato FHIR
      3. Se necessário, converte JSON proprietário -> FHIR
      4. Constrói Bundle transaction
      5. Envia para HAPI
      6. Move para processed/ ou error/

    Suporta:
      ✓ Bundle FHIR
      ✓ Resource FHIR individual
      ✓ Lista de recursos FHIR
      ✓ JSON proprietário PACS
    """

    # ======================================================
    # 0. ADQUIRIR LOCK EXCLUSIVO (evitar múltiplos workers)
    # ======================================================
    lock_fd = _lock_file(json_file_path)
    if lock_fd is None:
        log.warning(
            f"{filename} — não foi possível adquirir lock "
            f"(outro worker está a processar); reenfileirando..."
        )
        return False

    try:
        log.info(f"A processar: {filename}")

        # ======================================================
        # 1. LEITURA DO JSON
        # ======================================================
        try:
            with open(json_file_path, "r", encoding="utf-8") as f:
                data = json.load(f)

        except (OSError, json.JSONDecodeError) as exc:
            log.error(f"{filename} — erro de leitura/parse: {exc}")
            _mover(json_file_path, ERROR_DIR, filename)
            return False

        # ======================================================
        # 2. DETEÇÃO AUTOMÁTICA DE FORMATO FHIR
        # ======================================================
        try:

            resources = []

            # --------------------------------------------------
            # CASO A: Bundle FHIR
            # --------------------------------------------------
            if (
                isinstance(data, dict)
                and data.get("resourceType") == "Bundle"
            ):
                log.info(f"{filename} — Bundle FHIR detectado")
                resources = normalize(data)

            # --------------------------------------------------
            # CASO B: Recurso FHIR individual
            # --------------------------------------------------
            elif (
                isinstance(data, dict)
                and "resourceType" in data
            ):
                log.info(
                    f"{filename} — Recurso FHIR detectado: "
                    f"{data.get('resourceType')}"
                )
                resources = [data]

            # --------------------------------------------------
            # CASO C: Lista de recursos FHIR
            # --------------------------------------------------
            elif (
                isinstance(data, list)
                and all(
                    isinstance(r, dict)
                    and "resourceType" in r
                    for r in data
                )
            ):
                log.info(
                    f"{filename} — Lista de recursos FHIR detectada"
                )
                resources = data

            # --------------------------------------------------
            # CASO D: JSON proprietário -> converter
            # --------------------------------------------------
            else:
                log.info(
                    f"{filename} — JSON proprietário detectado; "
                    f"a converter para FHIR"
                )

                fhir_output = build_resources(data)
                resources = normalize(fhir_output)

        except Exception as exc:
            log.error(f"{filename} — erro na conversão/deteção FHIR: {exc}")
            _mover(json_file_path, ERROR_DIR, filename)
            return False

        # ======================================================
        # 3. VALIDAR RECURSOS
        # ======================================================
        if not resources:
            log.warning(f"{filename} — nenhum recurso gerado")
            _mover(json_file_path, ERROR_DIR, filename)
            return False

        log.info(
            f"{filename} — {len(resources)} recurso(s): "
            + ", ".join(
                r.get("resourceType", "?")
                for r in resources
            )
        )

        # ======================================================
        # 4. BUILD BUNDLE
        # ======================================================
        try:
            bundle = build_bundle(resources)

        except Exception as exc:
            log.error(f"{filename} — erro a construir Bundle: {exc}")
            _mover(json_file_path, ERROR_DIR, filename)
            return False

        # ======================================================
        # 5. ENVIAR PARA HAPI
        # ======================================================
        response = send_bundle(bundle)

        if not response:
            log.error(f"{filename} — erro no envio para HAPI")
            _mover(json_file_path, ERROR_DIR, filename)
            return False

        # ======================================================
        # 6. SUCCESS
        # ======================================================
        _mover(json_file_path, PROCESSED_DIR, filename)

        log.info(f"{filename} — ingestão concluída com sucesso ✓")

        return True

    finally:
        # Sempre libertar o lock, mesmo em caso de erro
        _unlock_file(lock_fd)

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
    Processa lista de ficheiros em paralelo com retry automático.
    
    Se um ficheiro falhar por lock (outro worker processando),
    será reenfileirado e tentado novamente.
    
    Retorna (sucesso, erros)
    """
    sucesso = 0
    erros = 0
    fila_retry = list(file_list)  # ficheiros para retentar
    tentativas_max = 3
    tentativa_atual = 0
    
    if not file_list:
        log.info("Nenhum ficheiro para processar")
        return 0, 0
    
    while fila_retry and tentativa_atual < tentativas_max:
        tentativa_atual += 1
        processados_nesta_tentativa = []
        
        log.info(f"Iniciando processamento de {len(fila_retry)} ficheiro(s) [tentativa {tentativa_atual}/{tentativas_max}]")
        
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            futures = {
                executor.submit(
                    ingestar_ficheiro,
                    os.path.join(INPUT_DIR, filename),
                    filename,
                ): filename
                for filename in fila_retry
            }

            for future in as_completed(futures):
                filename = futures[future]
                try:
                    ok = future.result()
                    if ok:
                        sucesso += 1
                        processados_nesta_tentativa.append(filename)
                    else:
                        # False significa lock falhou, não erro permanente
                        pass
                except Exception as exc:
                    log.error(f"Erro inesperado em {filename}: {exc}")
                    erros += 1
                    processados_nesta_tentativa.append(filename)
        
        # Atualizar fila: remover os que foram bem sucesso ou tiveram erro permanente
        fila_retry = [f for f in fila_retry if f not in processados_nesta_tentativa]
        
        if fila_retry:
            log.info(f"⏳ Aguardando 2s antes de retry para {len(fila_retry)} ficheiro(s)...")
            time.sleep(2)
    
    # Ficheiros que não foram processados após todas tentativas
    if fila_retry:
        log.error(f"⚠️  {len(fila_retry)} ficheiro(s) não puderam ser processados após {tentativas_max} tentativas:")
        for fname in fila_retry:
            log.error(f"   ✗ {fname}")
            erros += 1
    
    return sucesso, erros


def run_once():
    """Processa ficheiros que existem em data/input/ neste momento"""
    global auth_manager, AUTH_ENABLED
    
    start_time = time.time()
    
    setup_directories()
    
    # Inicializar autenticação se ativada
    auth_enabled = AUTH_ENABLED
    if auth_enabled:
        if not AUTH_PASSWORD:
            log.error("PIPELINE_AUTH_PASSWORD não configurada. Autenticação desativada.")
            auth_enabled = False
        else:
            auth_manager = JwtAuthManager(
                auth_url=HAPI_URL,
                email=AUTH_EMAIL,
                password=AUTH_PASSWORD,
                cache_file=AUTH_CACHE_FILE,
            )
            if not auth_manager.get_access_token():
                log.error("Falha ao autenticar; encerrando pipeline")
                return
            AUTH_ENABLED = auth_enabled
    
    files = [f for f in os.listdir(INPUT_DIR) if f.endswith(".json")]
    if not files:
        log.info("Nenhum ficheiro JSON em data/input/")
        return
    
    sucesso, erros = process_files(files)
    elapsed = time.time() - start_time
    log.info(f"Pipeline concluída — ✓ {sucesso} sucesso(s)  ✗ {erros} erro(s)  de {len(files)} ficheiro(s) em {elapsed:.2f}s")


def run_with_autotrigger():
    """Auto-trigger contínuo: monitora data/input/ e processa automaticamente"""
    global auth_manager, AUTH_ENABLED
    
    setup_directories()
    
    # Inicializar autenticação se ativada
    auth_enabled = AUTH_ENABLED
    if auth_enabled:
        if not AUTH_PASSWORD:
            log.error("PIPELINE_AUTH_PASSWORD não configurada. Autenticação desativada.")
            auth_enabled = False
        else:
            auth_manager = JwtAuthManager(
                auth_url=HAPI_URL,
                email=AUTH_EMAIL,
                password=AUTH_PASSWORD,
                cache_file=AUTH_CACHE_FILE,
            )
            if not auth_manager.get_access_token():
                log.error("Falha ao autenticar; encerrando pipeline")
                return
            AUTH_ENABLED = auth_enabled
    
    poller = FilePoller(INPUT_DIR, poll_interval=POLL_INTERVAL)
    
    def on_new_files(filenames: List[str]):
        """Callback quando novos arquivos são detectados"""
        if not filenames:
            return
        
        log.info(f"🚀 Processando lote com {len(filenames)} ficheiro(s)...")
        
        # Timing apenas do processamento
        start_process = time.time()
        sucesso, erros = process_files(filenames)  # Processa tudo em paralelo!
        elapsed = time.time() - start_process
        
        log.info(f"✓ Lote concluído: {sucesso} sucesso(s), {erros} erro(s)")
        log.info(f"Pipeline concluída — ✓ {sucesso} sucesso(s)  ✗ {erros} erro(s)  de {len(filenames)} ficheiro(s) em {elapsed:.2f}s")
    
    poller.start_watching(on_new_files)


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