# Implementação de Gerenciamento de Contas de Utilizador

## Resumo das Mudanças

Implementadas funcionalidades completas de gerenciamento de contas de utilizador no FHIR Server com persistência em banco de dados.

## Arquivos Criados

### 1. Entidades JPA
- **[User.java](src/main/java/ca/uhn/fhir/jpa/starter/security/User.java)** - Entidade para representar utilizadores com roles, status de ativo/bloqueado, e campos de auditoria

### 2. Repositório
- **[UserRepository.java](src/main/java/ca/uhn/fhir/jpa/starter/security/UserRepository.java)** - Repositório com queries customizadas para:
  - Busca por username/email
  - Filtros por role, status ativo/bloqueado
  - Paginação com ordenação
  - Búsca combinada com múltiplos filtros

### 3. Serviços
- **[UserService.java](src/main/java/ca/uhn/fhir/jpa/starter/security/UserService.java)** - Serviço de negócio com:
  - Criação de novas contas (validação de username/email únicos, senha mínimo 6 caracteres)
  - Listar utilizadores com filtros
  - Bloquear/desbloquear contas
  - Ativar/desativar contas
  - Validação de credenciais com check de ativo/bloqueado
  - Atualização de roles
  - Verificação de permissões admin

### 4. Controllers
- **[UserAdminController.java](src/main/java/ca/uhn/fhir/jpa/starter/security/UserAdminController.java)** - Endpoints para admin:
  - `POST /admin/users` - Criar nova conta
  - `GET /admin/users` - Listar contas com filtros e paginação
  - `GET /admin/users/{id}` - Obter detalhes de um utilizador
  - `POST /admin/users/{id}/lock` - Bloquear conta
  - `POST /admin/users/{id}/unlock` - Desbloquear conta
  - `PUT /admin/users/{id}/roles` - Atualizar roles do utilizador
  - `DELETE /admin/users/{id}` - Deactivar conta

### 5. Tratamento de Erros
- **[GlobalExceptionHandler.java](src/main/java/ca/uhn/fhir/jpa/starter/security/GlobalExceptionHandler.java)** - Handler global que:
  - Converte todas as exceções em respostas JSON (não HTML)
  - Retorna 401 para autenticação falha
  - Retorna 403 para acesso negado (não mais HTML)
  - Inclui validação de erros com detalhes
  - Mantém formato consistente em todas as respostas

### 6. DTOs
- **[UserDtos.java](src/main/java/ca/uhn/fhir/jpa/starter/security/UserDtos.java)** - Classes de transferência de dados:
  - `CreateUserRequest` - Requisição de criação
  - `UserResponse` - Resposta com dados do utilizador
  - `UpdateUserRolesRequest` - Atualização de roles
  - `UserListResponse` - Resposta paginada
  - `ErrorResponse` - Resposta de erro padronizada

### 7. Configuração
- **[JpaAuditingConfig.java](src/main/java/ca/uhn/fhir/jpa/starter/config/JpaAuditingConfig.java)** - Configuração para auditoria automática de `createdBy` e `updatedBy`

## Arquivos Modificados

### 1. [AuthController.java](src/main/java/ca/uhn/fhir/jpa/starter/security/AuthController.java)
- Removidos hardcoded users
- Integração com UserService para validação de credenciais
- Endpoint `/auth/me` agora retorna dados completos do utilizador do banco de dados
- Removidos geradores de ID/email fake

### 2. [SecurityConfig.java](src/main/java/ca/uhn/fhir/jpa/starter/security/SecurityConfig.java)
- Adicionada proteção para `/admin/**` com requerimento de role ADMIN
- Ordenação correta de matchers de segurança

### 3. [Application.java](src/main/java/ca/uhn/fhir/jpa/starter/Application.java)
- Adicionada anotação `@EnableJpaAuditing` para suporte a campos de auditoria automática

### Migração de Banco de Dados
- **[V2__create_users_table.sql](src/main/resources/db/migration/V2__create_users_table.sql)** - Migração Flyway que:
  - Cria tabela `users` com schema completo
  - Cria tabela `user_roles` para many-to-many relationship
  - Insere utilizadores padrão:
    - Admin: `admin` / `admin123` (BCrypt hash)
    - Doctor: `doctor` / `doctor123` (BCrypt hash)
  - Cria índices para otimização

## Funcionalidades Implementadas

### ✅ Admin criar nova conta
```bash
POST /admin/users
Authorization: Bearer <admin_token>

{
  "username": "medico1",
  "email": "medico1@example.com",
  "password": "senha123",
  "firstName": "João",
  "lastName": "Silva",
  "roles": ["MEDICO"]
}

Response: 201 Created
{
  "success": true,
  "message": "User created successfully",
  "data": {
    "id": 1,
    "username": "medico1",
    "email": "medico1@example.com",
    "firstName": "João",
    "lastName": "Silva",
    "roles": ["MEDICO"],
    "active": true,
    "locked": false,
    ...
  }
}
```

### ✅ Admin listar todas as contas
```bash
GET /admin/users?page=0&size=20
Authorization: Bearer <admin_token>

Response: 200 OK
{
  "success": true,
  "message": "Users retrieved successfully",
  "data": [...],
  "pagination": {
    "currentPage": 0,
    "pageSize": 20,
    "totalElements": 5,
    "totalPages": 1,
    "hasNext": false,
    "hasPrevious": false
  }
}
```

### ✅ Pesquisa por nome ou email
```bash
GET /admin/users?search=joao
Authorization: Bearer <admin_token>

GET /admin/users?search=medico1@example.com
Authorization: Bearer <admin_token>
```

### ✅ Filtragem por role e estado
```bash
GET /admin/users?role=MEDICO&active=true&locked=false
Authorization: Bearer <admin_token>

GET /admin/users?role=ADMIN&locked=true
Authorization: Bearer <admin_token>
```

### ✅ Admin bloquear/desbloquear conta
```bash
POST /admin/users/{id}/lock
Authorization: Bearer <admin_token>

Response: 200 OK
{
  "success": true,
  "message": "User account locked successfully",
  "data": {
    "id": 1,
    "username": "medico1",
    "locked": true,
    ...
  }
}

POST /admin/users/{id}/unlock
Response: 200 OK (com locked: false)
```

### ✅ Exceções retornando JSON em vez de HTML
```bash
# Acesso não autenticado (403)
GET /admin/users

Response: 401 Unauthorized
{
  "success": false,
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication required",
  "path": "/admin/users",
  "timestamp": "2024-01-15T10:30:00"
}

# Acesso sem permissão (403)
GET /fhir/Patient (como MEDICO, se DELETE)

Response: 403 Forbidden
{
  "success": false,
  "status": 403,
  "error": "Forbidden",
  "message": "You do not have permission to access this resource",
  "path": "/fhir/Patient",
  "timestamp": "2024-01-15T10:30:00"
}
```

## Segurança e Validações

### Validações Implementadas
- Username e email únicos (constraints de database + aplicação)
- Senha mínimo 6 caracteres
- Roles válidas apenas: ADMIN ou MEDICO
- Pelo menos uma role obrigatória
- Contas bloqueadas/inativas não podem fazer login
- Mudanças de conta rastreadas com createdBy/updatedBy automático

### Auditoria
- Timestamp automático de criação/atualização
- Rastreamento de quem criou/modificou cada conta
- Campos read-only para createdAt (não pode ser modificado)

## Testes de Integração

Testar com credenciais padrão:

```bash
# Login como admin
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# Login como doctor
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"doctor","password":"doctor123"}'
```

## Próximas Melhorias Recomendadas

1. **Autenticação 2FA** - Adicionar suporte a TOTP
2. **Histórico de login** - Rastrear tentativas de login
3. **Reset de senha** - Fluxo de reset seguro por email
4. **Permissions granulares** - Ir além de roles simples (ACL/ABAC)
5. **API de edição de perfil** - Permitir utilizadores editarem seus próprios dados
6. **Exportação de auditoria** - Relatórios de atividades
7. **Rate limiting** - Proteção contra brute force
8. **OAuth2/OIDC** - Integração com provedores externos
