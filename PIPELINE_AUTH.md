# Pipeline FHIR com Autenticação JWT

## 📋 Visão Geral

O pipeline de ingestão agora suporta autenticação JWT, permitindo que apenas serviços autorizados façam upload de dados FHIR para o servidor.

### Mudanças Implementadas

✅ **Novo módulo `pipeline/auth.py`**
- `JwtAuthManager`: Gerencia login, renovação de tokens e re-autenticação automática
- Cache persistente de tokens em ficheiro
- Renovação automática antes da expiração

✅ **Pipeline atualizado (`pipeline_ingestao.py`)**
- Integração com autenticação JWT
- Adiciona token Bearer em todas as requisições
- Suporta ativar/desativar autenticação via env var

✅ **Docker Compose atualizado**
- Novas variáveis de ambiente para o pipeline
- Suporte a `PIPELINE_AUTH_PASSWORD` via .env

✅ **Script de setup (`create_pipeline_user.py`)**
- Cria automaticamente o service account do pipeline
- Valida credenciais após criação

---

## 🚀 Setup Rápido

### 1️⃣ Criar o Service Account no Servidor

```bash
# Opção A: Com argumentos CLI
python create_pipeline_user.py \
  --server http://localhost:8080 \
  --admin-email admin@example.com \
  --admin-password admin123 \
  --pipeline-email pipeline@fhirserver.local \
  --pipeline-password MySecurePassword123

# Opção B: Com variáveis de ambiente
export FHIR_SERVER_URL=http://localhost:8080
export ADMIN_EMAIL=admin@example.com
export ADMIN_PASSWORD=admin123
export PIPELINE_EMAIL=pipeline@fhirserver.local
export PIPELINE_PASSWORD=MySecurePassword123

python create_pipeline_user.py
```

**Resultado esperado:**
```
============================================================
CRIANDO SERVICE ACCOUNT PARA O PIPELINE
============================================================
Servidor: http://localhost:8080
Admin: admin@example.com
Pipeline User: pipeline@fhirserver.local

✓ Admin autenticado com sucesso
✓ Usuário criado com sucesso
  ID: uuid-12345
  Email: pipeline@fhirserver.local
  Roles: [INGESTION]

✓ Login do pipeline bem-sucedido
  Access Token: ey...
  Refresh Token disponível: True

============================================================
✓ SERVICE ACCOUNT CRIADO COM SUCESSO
============================================================
```

### 2️⃣ Configurar o Docker Compose

Adicione um ficheiro `.env` na raiz do projeto:

```env
# .env
PIPELINE_AUTH_PASSWORD=MySecurePassword123
```

Ou edite `docker/docker-compose.yml` directamente:

```yaml
ingestion-pipeline:
  environment:
    PIPELINE_AUTH_ENABLED: "true"
    PIPELINE_AUTH_EMAIL: "pipeline@fhirserver.local"
    PIPELINE_AUTH_PASSWORD: "MySecurePassword123"
```

### 3️⃣ Iniciar o Pipeline

```bash
# Com docker-compose
cd docker
docker-compose up -d ingestion-pipeline

# Ou localmente
python pipeline_ingestao.py --watch
```

---

## ⚙️ Variáveis de Ambiente

| Variável | Default | Descrição |
|----------|---------|-----------|
| `PIPELINE_AUTH_ENABLED` | `true` | Ativar/desativar autenticação JWT |
| `PIPELINE_AUTH_EMAIL` | `pipeline@fhirserver.local` | Email do service account |
| `PIPELINE_AUTH_PASSWORD` | (vazio) | **OBRIGATÓRIO** - Password do service account |
| `HAPI_URL` | `http://localhost:8080` | URL do servidor (sem `/fhir`) |
| `PIPELINE_WORKERS` | `8` | Número de threads paralelas |
| `PIPELINE_TIMEOUT` | `30` | Timeout de requisição (segundos) |
| `PIPELINE_POLL` | `5` | Intervalo de verificação de novos arquivos |

---

## 🔄 Fluxo de Autenticação

```
┌─────────────────────────────────────────────────────┐
│ 1. Pipeline inicia                                  │
│    └─> Lê PIPELINE_AUTH_PASSWORD                  │
└─────────────────────────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────┐
│ 2. Faz login com credenciais do service account    │
│    POST /auth/login                                 │
│    Body: {                                          │
│      "email_address": "pipeline@...",              │
│      "password": "..."                              │
│    }                                                │
└─────────────────────────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────┐
│ 3. Recebe access_token + refresh_token            │
│    Guarda em cache (.pipeline_auth_cache.json)     │
└─────────────────────────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────┐
│ 4. Para cada requisição ao FHIR:                   │
│    ├─> Verifica expiração do token                │
│    ├─> Se expirou, renova com refresh_token      │
│    └─> Adiciona "Authorization: Bearer <token>"   │
└─────────────────────────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────┐
│ 5. Servidor valida o token JWT                    │
│    ├─> Verifica assinatura                        │
│    ├─> Verifica expiração                         │
│    ├─> Verifica se está na blacklist             │
│    └─> Se válido, processa requisição             │
└─────────────────────────────────────────────────────┘
```

---

## 🧪 Testando o Pipeline com Autenticação

### 1. Colocar um ficheiro JSON em `data/input/`

```bash
cat > data/input/test_patient.json << 'EOF'
{
  "patient_id": "12345",
  "name": "João Silva",
  "birth_date": "1990-05-15",
  "gender": "male",
  "contact": "joao@example.com"
}
EOF
```

### 2. Executar o pipeline

```bash
# Uma única execução
python pipeline_ingestao.py

# Ou com auto-trigger (contínuo)
python pipeline_ingestao.py --watch
```

### 3. Verificar logs

```bash
# Ver logs em tempo real
tail -f logs/ingesta.log

# Procurar por erros de autenticação
grep -i "unauthorized\|token" logs/ingesta.log

# Verificar sucesso de ingestão
grep "✓\|✗" logs/ingesta.log
```

### 4. Validar no servidor

```bash
# Verificar se o Patient foi criado
curl http://localhost:8080/fhir/Patient | jq .
```

---

## 🔐 Segurança

### Boas Práticas

1. **Não commit de passwords**
   ```bash
   # ✗ ERRADO
   PIPELINE_AUTH_PASSWORD: "senha123"  # NO GIT!

   # ✓ CERTO
   # Use variáveis de ambiente ou .env (em .gitignore)
   ```

2. **Diferentes passwords para cada ambiente**
   ```bash
   # Desenvolvimento
   PIPELINE_AUTH_PASSWORD=dev_temp_password

   # Produção
   PIPELINE_AUTH_PASSWORD=$(openssl rand -base64 32)
   ```

3. **Role-based access control**
   - Pipeline tem role `INGESTION` (apenas lê/escreve dados)
   - Não pode criar/modificar usuários
   - Não pode mudar configurações do servidor

4. **Token expiration**
   - Access tokens: 15 minutos (curta duração)
   - Refresh tokens: 7 dias (renovação automática)
   - Após logout, ambos são revogados imediatamente

### Troubleshooting

#### "Unauthorized - Token missing or invalid"

```bash
# 1. Verificar se PIPELINE_AUTH_PASSWORD está configurado
echo $PIPELINE_AUTH_PASSWORD

# 2. Verificar credenciais do admin
python create_pipeline_user.py --skip-test

# 3. Recriar o service account
python create_pipeline_user.py --admin-email admin@example.com ...

# 4. Limpar cache de tokens
rm logs/.pipeline_auth_cache.json
```

#### "Login failed for user: pipeline@..."

```bash
# 1. Verificar se o usuário existe no banco
# (via admin panel ou API)

# 2. Testar login manualmente
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email_address": "pipeline@fhirserver.local",
    "password": "MyPassword"
  }'

# 3. Se retorna 401, o password está errado
# 4. Se retorna 404, o usuário não existe - recriar com create_pipeline_user.py
```

#### Pipeline não consegue ligar ao servidor

```bash
# 1. Verificar se HAPI_URL está correto
echo $HAPI_URL

# 2. Testar conectividade
curl http://localhost:8080/fhir/metadata

# 3. Em docker-compose, verificar se containers estão healthy
docker-compose ps
docker-compose logs hapi-fhir-jpaserver-start
```

---

## 📊 Arquitetura de Tokens

### Ciclo de Vida do Access Token

```
[Login] → Access Token (15 min) ──[Expirou?]──> [Renovar] → Novo Token
                                        ↓
                                      [Sim]
                                   ┌────────────────┐
                                   │ Refresh Token  │
                                   │ (7 dias)       │
                                   └────────────────┘
                                        ↓
                                   [Ainda válido?]
                                   /              \
                                [Sim]            [Não]
                                 ↓                ↓
                          [Renovar]          [Login]
```

### Token Blacklist

Quando o pipeline faz logout (ou servidor faz):
1. Access Token é adicionado à blacklist (revogado)
2. Refresh Token é adicionado à blacklist (revogado)
3. Qualquer requisição com esses tokens é rejeitada
4. Blacklist é limpa automaticamente a cada 5 minutos

---

## 📝 Exemplo de .env

```env
# Autenticação do Pipeline
PIPELINE_AUTH_PASSWORD=very_secure_password_here_123!@#

# URLs do servidor
FHIR_SERVER_URL=http://localhost:8080
ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=admin_secure_pass

# Pipeline
PIPELINE_AUTH_ENABLED=true
PIPELINE_AUTH_EMAIL=pipeline@fhirserver.local
PIPELINE_WORKERS=8
PIPELINE_TIMEOUT=30
PIPELINE_POLL=5
```

---

## 🎯 Próximos Passos

1. **HTTPS/TLS**
   - Configurar certificados SSL
   - Usar `https://` em produção

2. **OAuth2/OIDC**
   - Integrar com Keycloak para IDP centralizado
   - Suportar múltiplos serviços

3. **Rate Limiting**
   - Limitar requisições por minuto
   - Proteger contra DDoS

4. **Audit Trail**
   - Registrar quem fez login/logout
   - Auditar todas as ingestões

---

## 📚 Referências

- [JwtAuthManager](../pipeline/auth.py) - Módulo de autenticação
- [Pipeline](../pipeline_ingestao.py) - Script principal
- [SecurityConfig](../src/main/java/ca/uhn/fhir/jpa/starter/security/SecurityConfig.java) - Spring Security
- [AuthController](../src/main/java/ca/uhn/fhir/jpa/starter/security/AuthController.java) - Endpoints de auth

