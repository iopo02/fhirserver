# Mapeamento de Dados para FHIR

Este documento descreve a lógica de conversão utilizada na `pipeline_ingestao.py`.

## Recursos Utilizados

| Conceito Original | Recurso FHIR | Lógica de Mapeamento |
| :--- | :--- | :--- |
| ID do Utente | `Patient.id` | Prefixo `pat-` + ID original. |
| Nome Completo | `Patient.name` | Separado em `given` e `family`. |
| Relatório Clínico | `DiagnosticReport` | Texto convertido para **Base64**. |
| Data do Exame | `Observation.effective` | Formatação para ISO8601. |

## Notas Técnicas
* Todos os recursos mantêm uma referência (Reference) para o `Patient`.
* O encoding dos ficheiros de texto para o campo `content` (DiagnosticReport) segue a norma Base64 para garantir integridade.
