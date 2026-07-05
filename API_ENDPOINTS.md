# 🌐 Endpoints do Servidor HAPI FHIR

Referência dos endpoints essenciais do servidor HAPI FHIR JPA para consumo pelo Dashboard (Projeto B) e pela pipeline de ingestão.

**Base URL:**
```
http://localhost:8080/fhir
```

---

## 1. Operações CRUD Standard

| Método | URL | Descrição |
| :--- | :--- | :--- |
| `GET` | `/fhir/Patient/{id}` | Ler um paciente pelo ID lógico FHIR (ex: `patient-25`). |
| `PUT` | `/fhir/Patient/{id}` | Criar ou substituir um paciente (idempotente). Usado pela pipeline. |
| `POST` | `/fhir/Patient` | Criar paciente com ID gerado pelo servidor. |
| `DELETE` | `/fhir/Patient/{id}` | Eliminar um paciente (soft-delete no HAPI). |
| `GET` | `/fhir/Patient/{id}/_history` | Ver todas as versões do recurso (audit trail completo). |
| `GET` | `/fhir/Patient/{id}/_history/{vid}` | Ver uma versão específica do recurso. |

> O mesmo padrão aplica-se a todos os outros tipos de recursos: `DiagnosticReport`, `Observation`, `DocumentReference`, etc.

---

## 2. Pesquisa de Pacientes

O HAPI FHIR suporta múltiplos parâmetros de pesquisa definidos pela especificação FHIR R4:

| Endpoint | Descrição |
| :--- | :--- |
| `GET /fhir/Patient?_id=patient-25` | Pesquisa por ID lógico FHIR. |
| `GET /fhir/Patient?identifier=12345` | Pesquisa pelo identificador hospitalar original. |
| `GET /fhir/Patient?birthdate=1990-06-12` | Pesquisa por data de nascimento exacta. |
| `GET /fhir/Patient?birthdate=gt1980-01-01` | Pesquisa por intervalo de datas (`gt`/`lt`/`ge`/`le`). |
| `GET /fhir/Patient?name=Serrano` | Pesquisa por nome (parcial, case-insensitive). |
| `GET /fhir/Patient?gender=female` | Pesquisa por género. |
| `GET /fhir/Patient?_sort=-_lastUpdated&_count=10` | 10 pacientes mais recentes (ordenação descendente). |
| `GET /fhir/Patient?_lastUpdated=gt2026-06-01` | Pacientes inseridos/atualizados após uma data. |
| `GET /fhir/Patient?_count=20&_offset=0` | Paginação: página 1 com 20 resultados. |

---

## 3. Pesquisa com Recursos Relacionados (`_revinclude`)

O parâmetro `_revinclude` permite obter recursos relacionados num único pedido, eliminando o problema das **N+1 queries**:

| Endpoint | Devolve |
| :--- | :--- |
| `GET /fhir/Patient/pat-25?_revinclude=DiagnosticReport:subject` | Patient + todos os DiagnosticReports. |
| `GET /fhir/Patient/pat-25?_revinclude=Observation:subject` | Patient + todas as Observations. |
| `GET /fhir/Patient/pat-25?_revinclude=*` | Patient + todos os recursos que o referenciam. |
| `GET /fhir/DiagnosticReport?subject=Patient/pat-25` | Todos os relatórios de um paciente. |
| `GET /fhir/Observation?subject=Patient/pat-25&_sort=-date` | Observações ordenadas por data descendente. |

---

## 4. Bundle Transaction (ingestão massiva)

| Método | URL | Descrição |
| :--- | :--- | :--- |
| `POST` | `/fhir` | **Bundle transaction** (`type: transaction`): todos os recursos processados atomicamente. Se um falhar, todos falham. |
| `POST` | `/fhir` | **Bundle batch** (`type: batch`): cada entrada processada independentemente, erros parciais permitidos. |

---

## 5. Operações Custom FHIR (`$`)

As operações FHIR são identificadas pelo prefixo `$` e ficam registadas automaticamente no `CapabilityStatement` do servidor:

| Endpoint | Descrição |
| :--- | :--- |
| `GET /fhir/Patient/{id}/$summary` | ⭐ **Implementada neste projeto.** Devolve Bundle com DiagnosticReport contendo resumo extractivo (PT) e abstractivo (EN). Aceita parâmetros `obsLimit`, `since`, `status`. |
| `POST /fhir/Patient/$everything` | Devolver todos os recursos relacionados com um paciente. |
| `GET /fhir/Patient/$match` | Encontrar pacientes duplicados por dados demográficos. |
| `GET /fhir/ValueSet/$expand` | Expandir um ValueSet (terminologia clínica). |
| `POST /fhir/Patient/$validate` | Validar um recurso Patient contra a especificação FHIR R4. Devolve `OperationOutcome`. |
| `GET /fhir/metadata` | **CapabilityStatement**: lista todas as operações e recursos suportados pelo servidor. |

---

## 6. Notificações em Tempo Real (WebSocket)

Para além dos endpoints REST, o servidor expõe um endpoint WebSocket para notificação em tempo real quando novos pacientes são inseridos:

| Endpoint | Descrição |
| :--- | :--- |
| `ws://localhost:8080/ws-patients` | Endpoint WebSocket STOMP com SockJS fallback. |
| `/topic/patients` (tópico STOMP) | Tópico onde são publicadas notificações de novos pacientes. |
| `GET /fhir/Patient/{id}` | Endpoint HAPI nativo chamado pelo Dashboard após receber a notificação WebSocket. |

**Fluxo de integração:**

```
Pipeline INSERT
     ↓
PostgreSQL NOTIFY → PatientNotificationListener
     ↓
WebSocket /topic/patients
     → { resourceId: "Patient/pat-25", patientName: "Samuel Serrano", timestamp: "..." }
     ↓
Dashboard: GET /fhir/Patient/pat-25
     → Recurso Patient completo em FHIR JSON
```

**Payload da notificação WebSocket:**

```json
{
  "resourceId":        "Patient/pat-25",
  "patientName":       "Samuel Serrano",
  "birthDate":         "1990-06-12",
  "gender":            "female",
  "patientIdentifier": "urn:oid:2.16.840.1.113883.4.1|12345",
  "timestamp":         "2026-06-10T10:54:33Z",
  "resourceType":      "Patient",
  "summaryUrl":        "/fhir/Patient/pat-25/$summary"
}
```

---

## 7. Estatísticas de Recursos (NEW)

⭐ **Base URL:** `/api/statistics`

Endpoints para obter estatísticas agregadas e agrupadas de recursos FHIR. Úteis para dashboards, relatórios operacionais e monitoramento.

| Método | URL | Descrição |
| :--- | :--- | :--- |
| `GET` | `/api/statistics/diagnostic-report/by-status` | Contagem de DiagnosticReports agrupados por status (final, preliminary, amended, etc). |
| `GET` | `/api/statistics/diagnostic-report/by-category` | Contagem de DiagnosticReports agrupados por categoria (imaging, laboratory, etc). |
| `GET` | `/api/statistics/imaging-study/by-category` | Contagem de ImagingStudies agrupados por modalidade (CT, MR, US, XC, etc). |
| `GET` | `/api/statistics/imaging-study/by-status` | Contagem de ImagingStudies agrupados por status (available, unavailable, entered-in-error). |

### Formato de Response

Todos os endpoints retornam o seguinte formato:

```json
{
  "timestamp": "2026-07-05T10:30:00.123456Z",
  "resourceType": "DiagnosticReport",
  "groupBy": "status",
  "data": [
    { "name": "final", "count": 1250 },
    { "name": "preliminary", "count": 450 },
    { "name": "amended", "count": 100 }
  ],
  "total": 1800
}
```

**Campos:**
- `timestamp` (ISO 8601): Data/hora da query em UTC
- `resourceType`: Tipo de recurso FHIR consultado
- `groupBy`: Critério de agrupamento (status, category, etc)
- `data`: Array com items agrupados (`name` + `count`)
- `total`: Soma de todos os counts

### Exemplos de Uso

```bash
# DiagnosticReport por Status
curl -X GET http://localhost:8080/api/statistics/diagnostic-report/by-status

# DiagnosticReport por Categoria
curl -X GET http://localhost:8080/api/statistics/diagnostic-report/by-category

# ImagingStudy por Categoria/Modalidade
curl -X GET http://localhost:8080/api/statistics/imaging-study/by-category

# ImagingStudy por Status
curl -X GET http://localhost:8080/api/statistics/imaging-study/by-status
```

### Performance

- Tipicamente **< 100ms** com índices apropriados
- Usa queries SQL nativas com `GROUP BY` no PostgreSQL
- Escalável até milhões de recursos

### Casos de Uso

- 📊 **Dashboards**: Gráficos de distribuição de recursos em tempo real
- 📈 **Relatórios**: Análise de volume por tipo de recurso
- 🔍 **Monitoramento**: Detectar padrões anormais no ingresso
- 🎯 **Planejamento**: Demanda por tipo de exame (CT vs MR vs US)