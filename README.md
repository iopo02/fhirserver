# Projeto 52 – Servidor HAPI FHIR com Pipeline Automática de Ingestão

## Descrição

Este projeto implementa um servidor **HL7 FHIR R4** baseado em **HAPI FHIR**, complementado por uma pipeline automática de ingestão de dados clínicos desenvolvida em Python.

A pipeline monitoriza continuamente a pasta de entrada, processa ficheiros clínicos, realiza a normalização necessária dos recursos e envia Bundles de transação para o servidor HAPI FHIR, permitindo a persistência dos dados em PostgreSQL.

---

## Requisitos

* Docker
* Docker Compose

---

## Estrutura do Projeto

```
fhirserver/
├── docker/
├── data/
│   ├── input/
│   ├── processed/
│   └── error/
├── logs/
├── pipeline_ingestao.py
└── ...
```

---

## Inicialização

Entrar na pasta do projeto:

```bash
cd ~/Projeto52_HAPI_server/fhirserver
```

Iniciar todos os serviços:

```bash
cd docker
docker compose up -d
```

Verificar se todos os contentores estão em execução:

```bash
docker ps
```

---

## Pipeline de Ingestão

Executar a pipeline em modo de monitorização contínua:

```bash
cd ~/Projeto52_HAPI_server/fhirserver
python3 pipeline_ingestao.py --watch
```

A pipeline monitoriza automaticamente a pasta:

```
data/input/
```

Sempre que um novo ficheiro é colocado nesta pasta, este é processado automaticamente.

Em caso de sucesso:

```
data/processed/
```

Em caso de erro:

```
data/error/
```

---

## Funcionalidades da Pipeline

* Monitorização automática da pasta de entrada (*polling* periódico)
* Processamento paralelo de ficheiros
* Conversão de ficheiros JSON PACS para recursos FHIR (quando aplicável)
* Encaminhamento direto de Bundles FHIR já existentes
* Normalização dos identificadores dos recursos
* Construção e envio de Bundles Transaction
* Comunicação com o servidor HAPI FHIR
* Registo de erros em ficheiro de log

---

## Endereços da Aplicação

### Página Principal do Servidor

```
http://<IP>:8080/
```

### HAPI FHIR

```
http://<IP>:8080/fhir
```

### Capability Statement

```
http://<IP>:8080/fhir/metadata
```

### Swagger UI

```
http://<IP>:8080/swagger-ui/
```

---

## Funcionalidades Desenvolvidas

Para além das operações standard disponibilizadas pelo servidor HAPI FHIR, o Projeto 52 implementa um conjunto de funcionalidades adicionais desenvolvidas no âmbito da dissertação.

### Operação FHIR Personalizada – `$summary`

Disponibiliza um resumo clínico agregado de um paciente, reunindo a informação mais relevante dos recursos associados.

**Endpoint**

```text
GET /fhir/Patient/{id}/$summary
```

---

### Notificações em Tempo Real (WebSocket)

O servidor disponibiliza um endpoint WebSocket que permite notificar aplicações cliente sempre que ocorre a criação ou atualização de recursos clínicos.

**Endpoint**

```text
ws://<IP>:8080/ws-patients
```

Esta funcionalidade é utilizada pelo Dashboard Clínico (Projeto 51) para atualização automática da informação apresentada ao utilizador.

---

### Endpoints Estatísticos

O sistema disponibiliza um conjunto de endpoints REST para obtenção de estatísticas agregadas diretamente sobre os recursos armazenados.

Endpoints disponíveis:

```text
GET /api/statistics/diagnostic-report/by-status
GET /api/statistics/diagnostic-report/by-category
GET /api/statistics/imaging-study/by-status
GET /api/statistics/imaging-study/by-category
```

As respostas são disponibilizadas em formato JSON e incluem informação agregada utilizada pelo Dashboard Clínico.

---

## Autenticação

Conta disponível para demonstração:

**Email**

```
admin@email.com
```

**Password**

```
admin123
```

---

## Tecnologias Utilizadas

* Java 21
* Spring Boot
* HAPI FHIR R4
* PostgreSQL
* Python 3
* Docker
* Docker Compose
* JWT
* WebSocket

---

## Encerramento

Para terminar os serviços:

```bash
cd docker
docker compose down
```

---

## Observações

* O servidor utiliza PostgreSQL para persistência dos recursos FHIR.
* A pipeline pode ser executada independentemente do servidor, desde que este esteja disponível.
* Os dados clínicos processados são armazenados no servidor HAPI FHIR através de Bundles Transaction.
* A máquina virtual foi preparada para permitir a reprodução integral do ambiente utilizado durante o desenvolvimento do projeto.
