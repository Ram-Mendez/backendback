# RAM Backend

Backend monolitico modular con Spring Boot, PostgreSQL, Flyway, JWT, roles/permisos y API REST para gestion de reclamaciones.

## Stack

- Java 21
- Spring Boot 4.1.1
- Spring Security stateless con JWT
- Spring Data JPA
- PostgreSQL
- Flyway
- Springdoc OpenAPI
- Spring Boot Actuator
- JUnit 5, Mockito y Testcontainers

## Requisitos

- JDK 21
- Maven 3.9+
- PostgreSQL 16 recomendado
- Docker solo para Testcontainers o `compose.yaml`

## Configuracion PostgreSQL

La configuracion base usa variables con defaults locales:

```properties
DB_URL=jdbc:postgresql://localhost:5433/ram
DB_USERNAME=ram
DB_PASSWORD=ram
```

Para levantar solo PostgreSQL de desarrollo:

```powershell
docker compose up -d postgres
```

## Variables de entorno

Ver `.env.example`.

Variables principales:

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET`
- `JWT_ACCESS_TOKEN_TTL`
- `JWT_REFRESH_TOKEN_TTL`
- `APP_CORS_ALLOWED_ORIGINS`
- `SWAGGER_ENABLED`

## Perfiles

- `default`: migraciones versionadas, PostgreSQL local, Swagger disponible.
- `dev`: anade `devdata` Flyway y logging de aplicacion en DEBUG.
- `test`: Testcontainers PostgreSQL y seeds DEV aislados.
- `prod`: exige secrets desde entorno, no carga devdata y deshabilita Swagger salvo `SWAGGER_ENABLED=true`.

## Arranque local

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

O con wrapper:

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

## Tests

```powershell
mvn clean test
mvn clean verify
```

Los tests de integracion usan PostgreSQL via Testcontainers cuando Docker esta disponible.

## Flyway

Flyway ejecuta automaticamente las migraciones al arrancar:

- `classpath:db/migration` para schema y catalogo esencial.
- `classpath:devdata` solo en perfiles `dev` y `test`.

Hibernate valida el schema con `spring.jpa.hibernate.ddl-auto=validate`.

## Swagger

- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`

La API usa bearer JWT.

## Usuarios DEV

Disponibles solo cuando se carga `devdata`:

- `user@local.dev` / `DevUser123!`
- `manager@local.dev` / `DevManager123!`
- `admin@local.dev` / `DevAdmin123!`

## Endpoints principales

Auth:

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/auth/me`

Claims:

- `GET /api/v1/claims?page=0&size=20&sort=createdAt,desc&status=DRAFT&search=text`
- `GET /api/v1/claims/{id}`
- `POST /api/v1/claims`
- `PUT /api/v1/claims/{id}`
- `PATCH /api/v1/claims/{id}/status`

No hay borrado fisico de reclamaciones en la API.

## Health

- `GET /actuator/health`
- `GET /actuator/info`

Solo se exponen `health` e `info`.

## Docker

PostgreSQL:

```powershell
docker compose up -d postgres
```

App + PostgreSQL:

```powershell
docker compose --profile app up --build
```

## Arquitectura

Estructura por feature:

- `auth`: login, refresh, logout, perfil
- `security`: JWT, roles, permisos, handlers REST
- `claim`: entidad, repository, specifications, service, DTOs, mapper, controller
- `exception`: manejo global de errores
- `config`: OpenAPI, clock y correlation id
