# API de Gerenciamento de Utilizadores - Endpoints

## Autenticação

### Login
```
POST /auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}

Response: 200 OK
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "username": "admin",
  "roles": ["ADMIN"]
}
```

### Refresh Token
```
POST /auth/refresh
Content-Type: application/json

{
  "refresh_token": "eyJhbGc..."
}

Response: 200 OK
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "username": "admin",
  "roles": ["ADMIN"]
}
```

### Logout
```
POST /auth/logout
Authorization: Bearer <access_token>

Response: 200 OK
{
  "message": "Logged out successfully",
  "timestamp": 1705318200000
}
```

### Get Current User
```
GET /auth/me
Authorization: Bearer <access_token>

Response: 200 OK
{
  "id": 1,
  "username": "admin",
  "email": "admin@fhirserver.com",
  "firstName": "Admin",
  "lastName": "User",
  "roles": ["ADMIN"],
  "active": true,
  "locked": false,
  "timestamp": 1705318200000
}
```

## Admin - Gerenciamento de Utilizadores

### Criar Nova Conta
```
POST /admin/users
Authorization: Bearer <admin_token>
Content-Type: application/json

{
  "username": "medico1",
  "email": "medico1@hospital.com",
  "password": "SenhaSegura123",
  "firstName": "João",
  "lastName": "Silva",
  "roles": ["MEDICO"]
}

Response: 201 Created
{
  "success": true,
  "message": "User created successfully",
  "data": {
    "id": 3,
    "username": "medico1",
    "email": "medico1@hospital.com",
    "firstName": "João",
    "lastName": "Silva",
    "roles": ["MEDICO"],
    "active": true,
    "locked": false,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-15T10:30:00",
    "createdBy": "admin",
    "updatedBy": "admin"
  }
}
```

### Listar Utilizadores
```
GET /admin/users?page=0&size=20
Authorization: Bearer <admin_token>

Response: 200 OK
{
  "success": true,
  "message": "Users retrieved successfully",
  "data": [
    {
      "id": 1,
      "username": "admin",
      "email": "admin@fhirserver.com",
      "firstName": "Admin",
      "lastName": "User",
      "roles": ["ADMIN"],
      "active": true,
      "locked": false,
      "createdAt": "2024-01-15T09:00:00",
      "updatedAt": "2024-01-15T09:00:00",
      "createdBy": "SYSTEM",
      "updatedBy": "SYSTEM"
    },
    ...
  ],
  "pagination": {
    "currentPage": 0,
    "pageSize": 20,
    "totalElements": 3,
    "totalPages": 1,
    "hasNext": false,
    "hasPrevious": false
  }
}
```

### Listar Utilizadores com Filtros
```
# Por role
GET /admin/users?role=MEDICO&page=0&size=20

# Por status ativo
GET /admin/users?active=true&page=0&size=20

# Por status bloqueado
GET /admin/users?locked=false&page=0&size=20

# Combinado
GET /admin/users?role=MEDICO&active=true&locked=false&page=0&size=20
```

### Pesquisar Utilizadores
```
# Por username
GET /admin/users?search=medico1&page=0&size=20

# Por email
GET /admin/users?search=medico1@hospital.com&page=0&size=20

# Com filtro
GET /admin/users?search=joao&active=true&page=0&size=20

Response: 200 OK (mesma estrutura de listagem)
```

### Obter Detalhes de um Utilizador
```
GET /admin/users/3
Authorization: Bearer <admin_token>

Response: 200 OK
{
  "success": true,
  "message": "User retrieved successfully",
  "data": {
    "id": 3,
    "username": "medico1",
    "email": "medico1@hospital.com",
    "firstName": "João",
    "lastName": "Silva",
    "roles": ["MEDICO"],
    "active": true,
    "locked": false,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-15T10:30:00",
    "createdBy": "admin",
    "updatedBy": "admin"
  }
}
```

### Bloquear Utilizador
```
POST /admin/users/3/lock
Authorization: Bearer <admin_token>
Content-Type: application/json

{
  "reason": "Suspicions activity detected"
}

Response: 200 OK
{
  "success": true,
  "message": "User account locked successfully",
  "data": {
    "id": 3,
    "username": "medico1",
    ...
    "locked": true,
    "updatedAt": "2024-01-15T10:35:00",
    "updatedBy": "admin"
  }
}
```

### Desbloquear Utilizador
```
POST /admin/users/3/unlock
Authorization: Bearer <admin_token>

Response: 200 OK
{
  "success": true,
  "message": "User account unlocked successfully",
  "data": {
    "id": 3,
    "username": "medico1",
    ...
    "locked": false,
    "updatedAt": "2024-01-15T10:40:00",
    "updatedBy": "admin"
  }
}
```

### Atualizar Roles do Utilizador
```
PUT /admin/users/3/roles
Authorization: Bearer <admin_token>
Content-Type: application/json

{
  "roles": ["ADMIN", "MEDICO"]
}

Response: 200 OK
{
  "success": true,
  "message": "User roles updated successfully",
  "data": {
    "id": 3,
    "username": "medico1",
    ...
    "roles": ["ADMIN", "MEDICO"],
    "updatedAt": "2024-01-15T10:45:00",
    "updatedBy": "admin"
  }
}
```

### Deactivar Utilizador (DELETE)
```
DELETE /admin/users/3
Authorization: Bearer <admin_token>

Response: 200 OK
{
  "success": true,
  "message": "User account deactivated successfully",
  "data": {
    "id": 3,
    "username": "medico1",
    ...
    "active": false,
    "updatedAt": "2024-01-15T10:50:00",
    "updatedBy": "admin"
  }
}
```

## Erros e Respostas

### Erro de Autenticação (401)
```
Response: 401 Unauthorized
{
  "success": false,
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or revoked token",
  "path": "/admin/users",
  "timestamp": "2024-01-15T10:30:00"
}
```

### Erro de Autorização (403)
```
Response: 403 Forbidden
{
  "success": false,
  "status": 403,
  "error": "Forbidden",
  "message": "You do not have permission to access this resource",
  "path": "/admin/users",
  "timestamp": "2024-01-15T10:30:00"
}
```

### Erro de Validação (400)
```
Response: 400 Bad Request
{
  "success": false,
  "status": 400,
  "error": "Bad Request",
  "message": "Username 'admin' already exists",
  "path": "/admin/users",
  "timestamp": "2024-01-15T10:30:00"
}
```

### Recurso Não Encontrado (404)
```
Response: 404 Not Found
{
  "success": false,
  "status": 404,
  "error": "Not Found",
  "message": "User not found",
  "path": "/admin/users/999",
  "timestamp": "2024-01-15T10:30:00"
}
```

## Códigos de Status HTTP

| Status | Significado | Causa |
|--------|------------|-------|
| 200 | OK | Operação bem sucedida |
| 201 | Created | Recurso criado com sucesso |
| 400 | Bad Request | Validação falhou (username duplicado, role inválida, etc) |
| 401 | Unauthorized | Token inválido, expirado ou ausente |
| 403 | Forbidden | Utilizador não tem permissão (não é admin) |
| 404 | Not Found | Utilizador não encontrado |
| 500 | Internal Server Error | Erro no servidor |

## Notas de Segurança

1. **Tokens** - Sempre incluir header `Authorization: Bearer <token>`
2. **Senha** - Mínimo 6 caracteres, armazenada com BCrypt (não reversível)
3. **Roles** - Apenas `ADMIN` e `MEDICO` são válidas
4. **Auditoria** - Todas as mudanças rastreiam quem as fez e quando
5. **Bloqueio** - Contas bloqueadas não conseguem fazer login
6. **Deactivação** - Não deleta a conta, apenas marca como inativa

## Exemplo Completo - Criar Conta e Login

```bash
# 1. Login como admin
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | jq -r '.accessToken' > /tmp/token.txt

# 2. Guardar token
TOKEN=$(cat /tmp/token.txt)

# 3. Criar nova conta
curl -X POST http://localhost:8080/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "dr_silva",
    "email": "silva@hospital.com",
    "password": "DrSilva123",
    "firstName": "Pedro",
    "lastName": "Silva",
    "roles": ["MEDICO"]
  }'

# 4. Login com nova conta
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"dr_silva","password":"DrSilva123"}'
```
