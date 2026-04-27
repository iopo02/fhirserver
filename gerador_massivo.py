import requests
from faker import Faker
import time
import random
from datetime import datetime

fake = Faker()
BASE_URL = "http://localhost:8080/fhir"

def criar_paciente_completo(idx):
    p_id = f"pat-{idx}"
    obs_id = f"obs-{idx}"
    enc_id = f"enc-{idx}"
    
    # Gerar data no formato ISO 8601 correto: YYYY-MM-DDTHH:MM:SS
    data_consulta = fake.date_time_this_year().isoformat()
    data_nascimento = fake.date_of_birth(minimum_age=18, maximum_age=90).strftime('%Y-%m-%d')
    
    return [
        {
            "fullUrl": f"Patient/{p_id}",
            "resource": {
                "resourceType": "Patient",
                "id": p_id,
                "name": [{"family": fake.last_name(), "given": [fake.first_name()]}],
                "gender": fake.random_element(["male", "female"]),
                "birthDate": data_nascimento
            },
            "request": {"method": "PUT", "url": f"Patient/{p_id}"}
        },
        {
            "fullUrl": f"Encounter/{enc_id}",
            "resource": {
                "resourceType": "Encounter",
                "id": enc_id,
                "status": "finished",
                "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB"},
                "subject": {"reference": f"Patient/{p_id}"},
                "period": {"start": data_consulta} # Agora com o 'T'
            },
            "request": {"method": "PUT", "url": f"Encounter/{enc_id}"}
        },
        {
            "fullUrl": f"Observation/obs-{idx}",
            "resource": {
                "resourceType": "Observation",
                "id": obs_id,
                "status": "final",
                "code": {"coding": [{"system": "http://loinc.org", "code": "2339-0", "display": "Glucose"}]},
                "subject": {"reference": f"Patient/{p_id}"},
                "valueQuantity": {"value": round(random.uniform(70, 140), 1), "unit": "mg/dL"}
            },
            "request": {"method": "PUT", "url": f"Observation/obs-{idx}"}
        }
    ]

def executar_carga_final(total_pacientes, lote_tamanho=50):
    print(f"🚀 A iniciar carga de {total_pacientes} pacientes...")
    inicio = time.time()
    
    for i in range(0, total_pacientes, lote_tamanho):
        batch_entries = []
        for j in range(i, i + lote_tamanho):
            if j >= total_pacientes: break
            batch_entries.extend(criar_paciente_completo(j))
            
        bundle = {"resourceType": "Bundle", "type": "transaction", "entry": batch_entries}
        
        try:
            res = requests.post(BASE_URL, json=bundle, timeout=30)
            if res.status_code == 200:
                print(f"✅ Lote {i//lote_tamanho + 1} OK ({i + lote_tamanho}/{total_pacientes})")
            else:
                print(f"❌ Erro: {res.json()['issue'][0]['diagnostics']}")
        except Exception as e:
            print(f"💥 Falha: {e}")

    print(f"\n✨ Concluído em {(time.time() - inicio)/60:.2f} minutos.")

if __name__ == "__main__":
    # Tenta com 1000 primeiro para confirmar que o erro 'T' desapareceu
    executar_carga_final(100000)