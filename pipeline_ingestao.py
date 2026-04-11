#!/usr/bin/env python3
import os
import json
import requests
import logging
import shutil
from datetime import datetime

# ---------------------------------------------------------
# IMPORTANTE: Importa aqui a função do código que JÁ EXISTE
# ---------------------------------------------------------
from fhir import build_resources 

# Configurações de Diretórios e URLs
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
INPUT_DIR = os.path.join(BASE_DIR, "data", "input")
PROCESSED_DIR = os.path.join(BASE_DIR, "data", "processed")
ERROR_DIR = os.path.join(BASE_DIR, "data", "error")
HAPI_URL = "http://localhost:8080/fhir"
LOG_FILE = os.path.join(BASE_DIR, "logs", "ingesta.log")

# Configuração do Logging
logging.basicConfig(
    filename=LOG_FILE, 
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

def setup_directories():
    """Garante que as pastas necessárias existem."""
    for directory in [INPUT_DIR, PROCESSED_DIR, ERROR_DIR]:
        os.makedirs(directory, exist_ok=True)

def ingestar_archivo(json_file_path, filename):
    """Converte JSON a FHIR e envia para o HAPI FHIR."""
    print(f"\nA processar: {filename}...")
    logging.info(f"Iniciando processamento de {filename}")
    
    try:
        # 1. Ler o ficheiro JSON original
        with open(json_file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        # 2. Converter para FHIR
        fhir_resources = build_resources(data)
        
        # Variável para rastrear se houve algum erro nos recursos deste ficheiro
        teve_erro = False

        # 3. Enviar cada recurso para o HAPI FHIR
        resources_to_post = fhir_resources.get('entry', []) if isinstance(fhir_resources, dict) and 'entry' in fhir_resources else fhir_resources

        for item in resources_to_post:
            resource = item.get('resource', item) # Lida com estrutura Bundle ou lista normal
            resource_type = resource.get('resourceType')
            resource_id = resource.get('id')
            
            if not resource_type:
                continue

            url = f"{HAPI_URL}/{resource_type}/{resource_id}"
            
            print(f"{url}")
            # Fazer o PUT para a API do HAPI
            response = requests.put(
                url,
                json=resource,
                headers={"Content-Type": "application/fhir+json"}
            )
            
            print(f"{resource}")
            
            if response.status_code in [200, 201]:
                print(f"  ✓ {resource_type} criado/atualizado com sucesso.")
                logging.info(f"Sucesso: {resource_type} guardado. ID: {response.json().get('id', 'N/A')}")
            else:
                print(f"  ✗ Erro ao criar {resource_type}: {response.status_code}")
                logging.error(f"Erro no HAPI para {resource_type} (Ficheiro {filename}): {response.text}")
                teve_erro = True
                
        # 4. Mover o ficheiro com base no resultado
        if teve_erro:
            shutil.move(json_file_path, os.path.join(ERROR_DIR, filename))
            logging.warning(f"Ficheiro movido para a pasta de ERROS: {filename}")
        else:
            shutil.move(json_file_path, os.path.join(PROCESSED_DIR, filename))
            logging.info(f"Ficheiro processado e movido para PROCESSED: {filename}")

    except requests.exceptions.ConnectionError:
        print(f"  ✗ ERRO CRÍTICO: Não foi possível ligar ao HAPI FHIR em {HAPI_URL}")
        logging.error("Falha de conexão com o servidor HAPI FHIR. O Docker está a correr?")
    except Exception as e:
        print(f"  ✗ Erro inesperado ao processar {filename}: {str(e)}")
        logging.error(f"Erro inesperado no ficheiro {filename}: {str(e)}")
        shutil.move(json_file_path, os.path.join(ERROR_DIR, filename))

if __name__ == "__main__":
    setup_directories()
    
    archivos = [f for f in os.listdir(INPUT_DIR) if f.endswith('.json')]
    
    if not archivos:
        print(f"Não foram encontrados ficheiros JSON em {INPUT_DIR}")
        logging.info("Pipeline executada, mas não havia ficheiros novos.")
    else:
        print(f"Encontrados {len(archivos)} ficheiros JSON para processar.")
        for archivo in archivos:
            ingestar_archivo(os.path.join(INPUT_DIR, archivo), archivo)
            
    print("\nPipeline concluída. Consulta 'ingesta.log' para mais detalhes.")
