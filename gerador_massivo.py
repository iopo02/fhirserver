import requests
from faker import Faker
import concurrent.futures
import time

# Configurações iniciais
fake = Faker()
HAPI_URL = "http://localhost:8080/fhir/Patient"

def gerar_e_enviar_paciente(i):
    """Gera um paciente aleatório e envia para o servidor."""
    patient_id = f"test-patient-{i}"
    payload = {
        "resourceType": "Patient",
        "id": patient_id,
        "name": [{"family": fake.last_name(), "given": [fake.first_name()]}],
        "birthDate": str(fake.date_of_birth(minimum_age=18, maximum_age=90))
    }
    
    try:
        # Timeout de 10s para evitar que o script fique preso se o HAPI demorar
        requests.put(f"{HAPI_URL}/{patient_id}", json=payload, timeout=10)
    except Exception as e:
        print(f"Erro ao enviar paciente {i}: {e}")

def gerar_em_lotes(total_objetivo, tamanho_lote=5000):
    """Gere a ingestão em blocos para não sobrecarregar o PostgreSQL."""
    total_atual = 0
    while total_atual < total_objetivo:
        inicio = total_atual
        fim = min(total_atual + tamanho_lote, total_objetivo)
        
        print(f"\n>>> A processar lote: {inicio} até {fim} (Alvo: {total_objetivo})")
        
        # Usamos 15 workers para um equilíbrio entre velocidade e estabilidade
        with concurrent.futures.ThreadPoolExecutor(max_workers=15) as executor:
            executor.map(gerar_e_enviar_paciente, range(inicio, fim))
        
        total_atual = fim
        print(f"--- Lote concluído. Pausa técnica de 5s para o disco/RAM respirar... ---")
        time.sleep(5)

if __name__ == "__main__":
    print("Iniciando Stress Test do Repositório FHIR...")
    tempo_inicio = time.time()
    
    # Define aqui o total que queres atingir
    TOTAL_FINAL = 100000 
    
    gerar_em_lotes(TOTAL_FINAL)
    
    tempo_total = time.time() - tempo_inicio
    print(f"\n✅ CONCLUÍDO: {TOTAL_FINAL} pacientes processados.")
    print(f"Tempo total: {tempo_total/60:.2f} minutos.")