FROM python:3.11-slim

WORKDIR /app

# Instala dependências do sistema
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copia requirements e instala Python packages
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copia código da pipeline
COPY pipeline_ingestao.py .
COPY fhir.py .
COPY pipeline/ ./pipeline/

# Cria diretórios de dados
RUN mkdir -p /app/data/input \
    /app/data/processed \
    /app/data/error \
    /app/logs

# Volume mount points (definidos em docker-compose.yml)

# Healthcheck (tenta acessar o servidor HAPI)
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -f http://hapi-fhir-jpaserver-start:8080/fhir/metadata || exit 1

# Executa pipeline com auto-trigger (--watch)
CMD ["python", "pipeline_ingestao.py", "--watch"]
