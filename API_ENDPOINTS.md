# Guia de API - Repositório FHIR (Projeto A)

## URL Base
`http://localhost:8080/fhir`

## Formato de IDs
Todos os recursos foram migrados para IDs com prefixos para garantir compatibilidade com o servidor:
* **Pacientes**: `pat-{ID_ORIGINAL}` (Ex: `pat-1794619058957769552`)
* **Exames**: `diag-{ACCESSION}`
* **Observações**: `obs-{ACCESSION}`

## Pesquisa Combinada (O que o Dashboard precisa)
Para obter os dados de um paciente e todos os seus exames num único pedido:
`GET /Patient/pat-1794619058957769552?_revinclude=DiagnosticReport:subject&_revinclude=Observation:subject`

## Notas Técnicas
- **Relatórios**: Estão em `DocumentReference` com conteúdo RTF codificado em Base64.
- **Status**: Recursos validados aparecem como `final` ou `completed`.
