#!/usr/bin/env python3
"""
Pipeline de Ingestão FHIR
--------------------------
Melhorias face à versão original:
  1. PUT idempotente em vez de POST (RF03 / sem duplicados)
  2. Bundle transaction por ficheiro (1 chamada HTTP em vez de N)
  3. ThreadPoolExecutor para processamento paralelo (RF05 / RNF03)
  4. Retry automático com backoff exponencial (robustez de rede)
  5. Logging para stdout + ficheiro em simultâneo
"""

import json
import logging
import os
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# ---------------------------------------------------------
# Conversor FHIR existente (não alterado)
# ---------------------------------------------------------
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

FHIR_HEADERS  = {
    "Content-Type": "application/fhir+json",
    "Accept":       "application/fhir+json",
}

# ==========================================================
# LOGGING — stdout + ficheiro em simultâneo
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

    # --- 2. Conversão para FHIR ---
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
if __name__ == "__main__":
    setup_directories()

    # Recolher ficheiros JSON em input/
    files = [f for f in os.listdir(INPUT_DIR) if f.endswith(".json")]

    if not files:
        log.info("Nenhum ficheiro JSON encontrado em data/input/. Pipeline terminada.")
        sys.exit(0)

    log.info(f"{len(files)} ficheiro(s) encontrado(s). Workers: {MAX_WORKERS}")

    sucesso = 0
    erros   = 0

    # --- Processamento paralelo com ThreadPoolExecutor (RF05 / RNF03) ---
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(
                ingestar_ficheiro,
                os.path.join(INPUT_DIR, filename),
                filename,
            ): filename
            for filename in files
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
                # Excepção não capturada dentro da thread
                log.error(f"Erro inesperado na thread para {filename}: {exc}")
                erros += 1

    # --- Resumo final ---
    log.info(
        f"Pipeline concluída — ✓ {sucesso} sucesso(s)  ✗ {erros} erro(s)  "
        f"de {len(files)} ficheiro(s)"
    )
    sys.exit(0 if erros == 0 else 1)