# 🗺️ Mapeamento de Dados: PACS JSON ➡️ HL7 FHIR R4

Este documento descreve a lógica de transformação e extração de dados aplicada pela `pipeline_ingestao.py` ao converter relatórios do sistema PACS para o servidor HAPI FHIR.

---

## 📋 Resumo da Estrutura
O ficheiro original do PACS é decomposto em três recursos principais interligados, garantindo a integridade referencial através do ID do paciente.

---

## 1. Recurso: `Patient`
**Objetivo:** Criar a entidade do utente na base de dados.

| Campo Original (JSON) | Atributo FHIR | Lógica de Transformação |
| :--- | :--- | :--- |
| `PACS_Report.Patient.ID` | `id` | Formatado como `patient-{ID}` para evitar conflitos. |
| `PACS_Report.Patient.ID` | `identifier` | Armazenado como Identificador Oficial (`use: official`). |
| `PACS_Report.Patient.Birthdate` | `birthDate` | Conversão de `DD/MM/YYYY` para `YYYY-MM-DD`. |

---

## 2. Recurso: `DiagnosticReport`
**Objetivo:** Armazenar o relatório clínico e o status do exame.

| Campo Original (JSON) | Atributo FHIR | Lógica de Transformação |
| :--- | :--- | :--- |
| `Report.AccessionNumber` | `identifier` | Identificador único do exame (Accession Number). |
| `Report.Exam_Type` | `code.text` | Descrição do exame (ex: "TC do abdómen superior"). |
| `Patient.ID` | `subject.reference` | Ligação obrigatória ao recurso `Patient` criado. |
| `Report.Validation_Timestamp`| `effectiveDateTime` | Data de validação convertida para formato ISO8601. |
| `Report.Report` | `presentedForm.data` | **Codificação Crítica:** O texto RTF original é convertido para **Base64** para preservação de caracteres especiais e formatação. |
| `Report.Report` | `presentedForm.contentType`| Definido como `application/rtf`. |

---

## 3. Recurso: `Observation`
**Objetivo:** Isolar as notas de observação clínica para facilitar a pesquisa.

| Campo Original (JSON) | Atributo FHIR | Lógica de Transformação |
| :--- | :--- | :--- |
| `Report.Observation` | `valueString` | Texto bruto da observação/histórico clínico. |
| `Patient.ID` | `subject.reference` | Referência ao `Patient`. |
| `Report.Exam_Type` | `code.coding` | Categorizado como observação de "Imagiologia". |

---

## 🛠️ Notas Técnicas de Implementação

### 🔐 Tratamento de Base64
Para o campo `DiagnosticReport.presentedForm`, a pipeline utiliza a biblioteca `base64` do Python. Isto é essencial porque o campo `Report` original contém caracteres de escape do formato RTF (ex: `\\par`, `\\'e7`), que poderiam corromper o JSON da API se não fossem codificados.

### 📅 Parsing de Datas
As datas de entrada seguem o padrão Europeu (`DD/MM/YYYY`). A pipeline normaliza estas datas para o padrão FHIR (`YYYY-MM-DD`) antes do envio, garantindo que os **índices de data** do PostgreSQL funcionem corretamente para pesquisas de intervalos.

### 🔗 Integridade Referencial
O `DiagnosticReport` e a `Observation` são enviados num **Bundle** (ou sequencialmente) garantindo que a referência `subject` aponte corretamente para o `Patient` já existente no servidor.
