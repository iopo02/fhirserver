# pipeline/cleaning.py
import re

try:
    from striprtf.striprtf import rtf_to_text
except ImportError:
    raise ImportError("A biblioteca 'striprtf' não está instalada. Executa: pip install striprtf")

def clean_rtf(text: str, mode: str = "standard") -> str:
    """
    Recebe um texto (potencialmente em formato RTF) e devolve texto simples limpo.
    """
    if not text:
        return ""

    # 1. Se o texto for RTF (começa com {\rtf), convertemos para texto simples
    if "{\\rtf" in text or "\\rtf" in text:
        try:
            text = rtf_to_text(text)
        except Exception as e:
            print(f"Aviso: Falha ao converter RTF. Erro: {e}")
            # Se falhar, tenta limpar as tags RTF com uma expressão regular básica
            text = re.sub(r'\{.*?\}', '', text)
            text = re.sub(r'\\[a-z]+\d*\s?', '', text)

    # 2. Limpeza geral (aplicável a qualquer modo)
    # Substituir quebras de linha e tabulações por espaços simples
    text = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
    
    # Remover espaços múltiplos (ex: "    " vira " ")
    text = re.sub(r'\s+', ' ', text).strip()

    # 3. Limpeza agressiva (caso seja para a IA de resumo)
    if mode == "aggressive":
        # Remove caracteres muito estranhos mantendo letras, números e pontuação básica
        text = re.sub(r'[^\w\s\.,;:!?\-\(\)]', '', text)

    return text
