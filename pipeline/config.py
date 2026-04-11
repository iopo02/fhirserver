# pipeline/config.py

"""
Configurações para o pipeline de processamento FHIR.
"""

# Modo padrão de limpeza para os recursos FHIR
FHIR_CLEAN_MODE = "standard"

# Modo de limpeza para a funcionalidade de resumo (Summarization)
# Pode ser mais agressivo para remover pontuações extra ou metadados
SUMMARY_CLEAN_MODE = "aggressive"
