#!/usr/bin/env python3
import os
import json
import requests
import logging
import shutil

# ---------------------------------------------------------
# IMPORTANTE: conversor FHIR existente
# ---------------------------------------------------------
from fhir import build_resources

# Configurações
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
INPUT_DIR = os.path.join(BASE_DIR, "data", "input")
PROCESSED_DIR = os.path.join(BASE_DIR, "data", "processed")
ERROR_DIR = os.path.join(BASE_DIR, "data", "error")
HAPI_URL = "http://localhost:8080/fhir"
LOG_FILE = os.path.join(BASE_DIR, "logs", "ingesta.log")

logging.basicConfig(
    filename=LOG_FILE,
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)


def setup_directories():
    for directory in [INPUT_DIR, PROCESSED_DIR, ERROR_DIR]:
        os.makedirs(directory, exist_ok=True)


def normalize(resources):
    if isinstance(resources, list):
        return resources

    if isinstance(resources, dict):
        if "entry" in resources:
            return resources["entry"]

        if "resourceType" in resources:
            return [resources]

        return list(resources.values())

    return []


def create_resource(resource):
    """POST seguro ao HAPI"""
    resource_type = resource.get("resourceType")

    if not resource_type:
        return None

    url = f"{HAPI_URL}/{resource_type}"

    response = requests.post(
        url,
        json=resource,
        headers={"Content-Type": "application/fhir+json"}
    )

    if response.status_code not in [200, 201]:
        print(f"✗ Erro em {resource_type}: {response.status_code}")
        print(response.text)
        logging.error(f"Erro HAPI {resource_type}: {response.text}")
        return None

    return response.json()


def ingestar_archivo(json_file_path, filename):
    print(f"\nA processar: {filename}...")
    logging.info(f"Iniciando processamento de {filename}")

    try:
        with open(json_file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        fhir_resources = build_resources(data)
        resources_to_post = normalize(fhir_resources)

        if not resources_to_post:
            print("Nenhum recurso gerado pelo conversor.")
            logging.warning("Nenhum recurso gerado.")
            shutil.move(json_file_path, os.path.join(ERROR_DIR, filename))
            return

        # -----------------------------
        # 1. Separar Patient
        # -----------------------------
        patient_resource = None
        other_resources = []

        for item in resources_to_post:
            resource = item.get("resource", item)

            if resource.get("resourceType") == "Patient":
                patient_resource = resource
            else:
                other_resources.append(resource)

        if not patient_resource:
            print("Paciente não encontrado no bundle.")
            logging.error("Paciente não encontrado.")
            shutil.move(json_file_path, os.path.join(ERROR_DIR, filename))
            return

        # -----------------------------
        # 2. Criar Patient primeiro
        # -----------------------------
        patient_response = create_resource(patient_resource)

        if not patient_response:
            shutil.move(json_file_path, os.path.join(ERROR_DIR, filename))
            return

        patient_id = patient_response["id"]
        print(f"Patient criado com ID servidor: {patient_id}")

        # -----------------------------
        # 3. Criar restantes resources
        # -----------------------------
        teve_erro = False

        for resource in other_resources:
            resource_type = resource.get("resourceType")

            if not resource_type:
                continue

            # 🔥 FIX PRINCIPAL: atualizar referência Patient
            if "subject" in resource and isinstance(resource["subject"], dict):
                resource["subject"]["reference"] = f"Patient/{patient_id}"

            if "patient" in resource and isinstance(resource["patient"], dict):
                resource["patient"]["reference"] = f"Patient/{patient_id}"

            response_json = create_resource(resource)

            if response_json:
                print(f"✓ {resource_type} criado com sucesso")
                logging.info(f"{resource_type} criado")
            else:
                teve_erro = True

        # -----------------------------
        # 4. mover ficheiro
        # -----------------------------
        if teve_erro:
            shutil.move(json_file_path, os.path.join(ERROR_DIR, filename))
        else:
            shutil.move(json_file_path, os.path.join(PROCESSED_DIR, filename))

        logging.info(f"Ficheiro processado: {filename}")

    except Exception as e:
        print(f"Erro inesperado: {str(e)}")
        logging.error(f"Erro no ficheiro {filename}: {str(e)}")
        shutil.move(json_file_path, os.path.join(ERROR_DIR, filename))


if __name__ == "__main__":
    setup_directories()

    files = [f for f in os.listdir(INPUT_DIR) if f.endswith(".json")]

    if not files:
        print("Nenhum ficheiro encontrado.")
    else:
        print(f"{len(files)} ficheiros encontrados.")
        for file in files:
            ingestar_archivo(
                os.path.join(INPUT_DIR, file),
                file
            )

    print("\nPipeline concluída.")