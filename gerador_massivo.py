import requests
from faker import Faker
import time
import random

fake = Faker()
BASE_URL = "http://localhost:8080/fhir"


# -----------------------------
# DADOS CLÍNICOS REALISTAS
# -----------------------------
CONDITIONS = [
    "Pneumonia adquirida na comunidade",
    "Diabetes mellitus tipo 2",
    "Insuficiência renal crónica estadio 3",
    "Fibrilhação auricular",
    "Hipertensão arterial"
]

MEDICATIONS = [
    "Metformina 850mg 2x/dia",
    "Warfarina 5mg/dia",
    "Furosemida 40mg/dia",
    "Ceftriaxona 2g IV",
    "Paracetamol 1g SOS"
]

ALLERGIES = [
    "Penicilina",
    "AINEs",
    "Contraste iodado"
]


# -----------------------------
# GERADOR DE PACIENTE COMPLETO
# -----------------------------
def criar_paciente_completo(idx):

    p_id = f"pat-{idx}"
    enc_id = f"enc-{idx}"
    obs_id = f"obs-{idx}"
    cond_id = f"cond-{idx}"
    med_id = f"med-{idx}"
    allergy_id = f"allergy-{idx}"

    data_consulta = fake.date_time_this_year().isoformat()
    birth = fake.date_of_birth(minimum_age=18, maximum_age=90).strftime('%Y-%m-%d')

    condition = random.choice(CONDITIONS)
    medication = random.choice(MEDICATIONS)
    allergy = random.choice(ALLERGIES)

    observation_text = f"""
Paciente apresenta quadro de {random.choice(['febre alta', 'dispneia', 'dor torácica', 'confusão mental'])}.
Analiticamente: {random.choice(['leucocitose', 'PCR elevada', 'insuficiência renal agravada', 'hipoxemia'])}.
Iniciada terapêutica com {random.choice(['antibioterapia empírica', 'oxigenoterapia', 'fluidoterapia'])}.
Evolução {random.choice(['favorável', 'reservada', 'parcialmente favorável'])}.
"""

    glucose = round(random.uniform(70, 180), 1)


    return [

        # ---------------- PATIENT ----------------
        {
            "fullUrl": f"Patient/{p_id}",
            "resource": {
                "resourceType": "Patient",
                "id": p_id,
                "name": [{
                    "family": fake.last_name(),
                    "given": [fake.first_name()]
                }],
                "gender": random.choice(["male", "female"]),
                "birthDate": birth
            },
            "request": {
                "method": "PUT",
                "url": f"Patient/{p_id}"
            }
        },

        # ---------------- ENCOUNTER ----------------
        {
            "fullUrl": f"Encounter/{enc_id}",
            "resource": {
                "resourceType": "Encounter",
                "id": enc_id,
                "status": "finished",
                "class": {
                    "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                    "code": "IMP"
                },
                "subject": {"reference": f"Patient/{p_id}"},
                "period": {"start": data_consulta}
            },
            "request": {
                "method": "PUT",
                "url": f"Encounter/{enc_id}"
            }
        },

        # ---------------- CONDITION ----------------
        {
            "fullUrl": f"Condition/{cond_id}",
            "resource": {
                "resourceType": "Condition",
                "id": cond_id,
                "clinicalStatus": {
                    "coding": [{
                        "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                        "code": "active"
                    }]
                },
                "code": {"text": condition},
                "subject": {"reference": f"Patient/{p_id}"}
            },
            "request": {
                "method": "PUT",
                "url": f"Condition/{cond_id}"
            }
        },

        # ---------------- MEDICATION ----------------
        {
            "fullUrl": f"MedicationRequest/{med_id}",
            "resource": {
                "resourceType": "MedicationRequest",
                "id": med_id,
                "status": "active",
                "intent": "order",
                "medicationCodeableConcept": {"text": medication},
                "subject": {"reference": f"Patient/{p_id}"}
            },
            "request": {
                "method": "PUT",
                "url": f"MedicationRequest/{med_id}"
            }
        },

        # ---------------- ALLERGY ----------------
        {
            "fullUrl": f"AllergyIntolerance/{allergy_id}",
            "resource": {
                "resourceType": "AllergyIntolerance",
                "id": allergy_id,
                "clinicalStatus": {
                    "coding": [{
                        "system": "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical",
                        "code": "active"
                    }]
                },
                "code": {"text": allergy},
                "subject": {"reference": f"Patient/{p_id}"}
            },
            "request": {
                "method": "PUT",
                "url": f"AllergyIntolerance/{allergy_id}"
            }
        },

        # ---------------- OBSERVATION (NARRATIVA) ----------------
        {
            "fullUrl": f"Observation/{obs_id}",
            "resource": {
                "resourceType": "Observation",
                "id": obs_id,
                "status": "final",
                "code": {"text": "Evolução clínica"},
                "subject": {"reference": f"Patient/{p_id}"},
                "valueString": observation_text
            },
            "request": {
                "method": "PUT",
                "url": f"Observation/{obs_id}"
            }
        },

        # ---------------- LAB (GLUCOSE) ----------------
        {
            "fullUrl": f"Observation/glucose-{idx}",
            "resource": {
                "resourceType": "Observation",
                "id": f"glucose-{idx}",
                "status": "final",
                "code": {
                    "text": "Glucose"
                },
                "subject": {"reference": f"Patient/{p_id}"},
                "valueQuantity": {
                    "value": glucose,
                    "unit": "mg/dL"
                }
            },
            "request": {
                "method": "PUT",
                "url": f"Observation/glucose-{idx}"
            }
        }
    ]


# -----------------------------
# EXECUÇÃO DE CARGA
# -----------------------------
def executar_carga(total_pacientes, lote_tamanho=100):

    print(f"🚀 A iniciar carga de {total_pacientes} pacientes...")
    inicio = time.time()

    for i in range(0, total_pacientes, lote_tamanho):

        batch_entries = []

        for j in range(i, min(i + lote_tamanho, total_pacientes)):
            batch_entries.extend(criar_paciente_completo(j))

        bundle = {
            "resourceType": "Bundle",
            "type": "transaction",
            "entry": batch_entries
        }

        res = requests.post(BASE_URL, json=bundle, timeout=60)

        if res.status_code in [200, 201]:
            print(f"✅ Lote {i}-{i+lote_tamanho} OK")
        else:
            print("❌ Erro:", res.text)

    print(f"\n✨ Concluído em {(time.time() - inicio)/60:.2f} minutos")


if __name__ == "__main__":
    executar_carga(1000)