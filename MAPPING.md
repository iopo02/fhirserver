# 🗺️ Mapeamento de Dados: PACS JSON ➡️ HL7 FHIR R4

Este documento descreve a lógica de transformação e extração de dados aplicada pela `pipeline_ingestao.py` ao converter relatórios do sistema PACS para o servidor HAPI FHIR.

---

## 📋 Resumo da Estrutura

O ficheiro original do PACS é decomposto em múltiplos recursos FHIR R4 interligados, garantindo integridade referencial através do ID do paciente. Todos os recursos são enviados num único **Bundle transaction** com método **PUT idempotente**, evitando duplicação em caso de reprocessamento.

| Recurso FHIR | Origem | Objetivo |
| :--- | :--- | :--- |
| `Patient` | `PACS_Report.Patient` | Criar a entidade do utente na base de dados |
| `DiagnosticReport` | `PACS_Report.Report` | Armazenar o relatório clínico e status do exame |
| `Observation` | `Report.Observation` | Isolar notas clínicas para pesquisa eficiente |
| `DocumentReference` | `Report.Report` (RTF) | Preservar o laudo original em Base64 |
| `ImagingStudy` | `Report.AccessionNumber` | Registar o estudo de imagem DICOM |
| `Procedure` | `Report.Exam_Type` | Registar o procedimento realizado |

---

## 1. Recurso: `Patient`

**Objetivo:** Criar a entidade do utente na base de dados FHIR. O identificador hospitalar é preservado como Identifier oficial para garantir rastreabilidade.

| Campo JSON Original | Atributo FHIR | Lógica de Transformação |
| :--- | :--- | :--- |
| `PACS_Report.Patient.ID` | `id` | Formatado como `patient-{ID}`. Limpo com regex `[^A-Za-z0-9\-.]` para conformidade FHIR. Limite de 64 caracteres. |
| `PACS_Report.Patient.ID` | `identifier[0]` | Armazenado como Identificador Oficial (`use: official`) com `system: urn:oid:2.16.840.1.113883.4.1`. |
| `PACS_Report.Patient.Birthdate` | `birthDate` | Conversão de `DD/MM/YYYY` para `YYYY-MM-DD` (padrão FHIR R4). Suporta múltiplos formatos de entrada. |
| `PACS_Report.Patient.Gender` | `gender` | Normalização: `M`/`Masculino`/`1` → `male`; `F`/`Feminino`/`2` → `female`. Valores desconhecidos → `unknown`. |
| `PACS_Report.Patient.Name.*` | `name[0]` | `family` e `given` extraídos separadamente para pesquisa por apelido. |

---

## 2. Recurso: `DiagnosticReport`

**Objetivo:** Armazenar o relatório clínico completo, o status do exame e a ligação ao laudo RTF original.

| Campo JSON Original | Atributo FHIR | Lógica de Transformação |
| :--- | :--- | :--- |
| `Report.AccessionNumber` | `id` | Formatado como `diagnosticreport-{AccessionNumber}`. |
| `Report.Exam_Type` | `code.text` | Descrição livre do exame (ex: `TC do abdómen superior`). |
| `Patient.ID` | `subject.reference` | Referência obrigatória: `Patient/patient-{ID}`. |
| `Report.Validation_Timestamp` | `issued` | Convertido para ISO8601 com timezone UTC (formato FHIR R4). |
| `Report.Validation_Timestamp` | `status` | Se presente: `final`. Se ausente: `preliminary`. |
| `Report.Observation_clean` | `conclusion` | Texto da observação clínica após limpeza de marcas RTF. |

---

## 3. Recurso: `DocumentReference`

**Objetivo:** Preservar o laudo clínico original em formato RTF dentro do recurso FHIR, sem perda de formatação ou caracteres especiais.

| Campo JSON Original | Atributo FHIR | Lógica de Transformação |
| :--- | :--- | :--- |
| `Report.Report` (RTF) | `content[0].attachment.data` | **Codificação Base64 obrigatória:** `base64.b64encode(rtf.encode('utf-8')).decode('ascii')`. |
| — | `content[0].attachment.contentType` | Fixo: `application/rtf`. |
| `Patient.ID` | `subject.reference` | Referência ao Patient criado. |
| — | `status` | Fixo: `current`. |
| — | `type` | LOINC `18748-4` (Diagnostic imaging study) para conformidade FHIR R4. |

---

## 4. Recurso: `Observation`

**Objetivo:** Isolar as notas de observação clínica para facilitar a pesquisa por conteúdo textual nos índices do HAPI FHIR.

| Campo JSON Original | Atributo FHIR | Lógica de Transformação |
| :--- | :--- | :--- |
| `Report.Observation` | `valueString` | Texto bruto após limpeza RTF (`clean_rtf`). |
| `Patient.ID` | `subject.reference` | Referência ao Patient. |
| — | `code.text` | Fixo: `Radiology narrative`. |
| — | `status` | Fixo: `final`. |

---

## 🛠️ Notas Técnicas de Implementação

### 🔐 Tratamento de Base64 (RTF)

Para o campo `DocumentReference.content[0].attachment.data`, a pipeline utiliza a biblioteca `base64` do Python:

```python
b64 = base64.b64encode(str(rtf).encode('utf-8', errors='ignore')).decode('ascii')
```

Isto é essencial porque o campo `Report` original contém caracteres de escape do formato RTF (ex: `\\par`, `\\'e7`) que corromperiam o JSON da API se não fossem codificados. O Dashboard (Projeto B) descodifica com `atob()` no browser.

---

### 📅 Parsing de Datas

As datas de entrada seguem o padrão Europeu (`DD/MM/YYYY`). A função `to_iso()` normaliza para o padrão FHIR R4 suportando múltiplos formatos:

```python
fmts = (
    "%d/%m/%Y %H:%M:%S",
    "%d/%m/%Y %H:%M",
    "%d/%m/%Y",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%d",
)
```

A normalização é crítica para que os índices de data do PostgreSQL (tabelas `HFJ_SPIDX_DATE`) funcionem corretamente em pesquisas de intervalos.

---

### 🔗 Integridade Referencial

O `DiagnosticReport`, a `Observation` e o `DocumentReference` são enviados no mesmo **Bundle transaction**, garantindo que a referência `subject` aponta corretamente para o `Patient` já existente no servidor:

```json
{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    {
      "resource": { "resourceType": "Patient", "id": "patient-123" },
      "request": { "method": "PUT", "url": "Patient/patient-123" }
    },
    {
      "resource": { "resourceType": "DiagnosticReport", "id": "diagnosticreport-ACC001" },
      "request": { "method": "PUT", "url": "DiagnosticReport/diagnosticreport-ACC001" }
    }
  ]
}
```

O uso de `PUT` garante que reprocessamentos não geram duplicados — o HAPI atualiza o recurso existente (RF03).

---

### ✅ Conformidade com HL7 FHIR R4

| Mecanismo | Implementação |
| :--- | :--- |
| **IDs válidos** | Regex `[^A-Za-z0-9\-.]` garante caracteres permitidos pelo FHIR. Limite de 64 caracteres. |
| **Datas ISO8601** | Todas as datas convertidas para `YYYY-MM-DD` ou `YYYY-MM-DDTHH:MM:SSZ`. |
| **Referências válidas** | `subject.reference` usa sempre o formato `ResourceType/id` (ex: `Patient/patient-25`). |
| **Status obrigatórios** | Valor derivado da presença de `Validation_Timestamp` (`final` ou `preliminary`). |
| **Content-Type** | Todos os pedidos usam `Content-Type: application/fhir+json` e `Accept: application/fhir+json`. |