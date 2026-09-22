# Guía de comportamiento esperado de RAM

Referencia: `main` en `a89ace7c6d16`. Describe el contrato funcional observable; las referencias al final permiten localizar su respaldo en código y pruebas de esa revisión. Las respuestas de error usan `ProblemDetail` con `status` HTTP y propiedad `code` [E].

## 1. Visión general

Una **Claim** es una reclamación creada por un usuario autenticado. Tiene autor inmutable, referencia pública única, datos descriptivos, prioridad, plazo opcional, responsable revisor opcional, adjuntos, comentarios e historial. Nace en `DRAFT`; su autor la registra, un revisor la examina y decide si la acepta, rechaza, declara inadmisible o devuelve para corrección. Tras corregirla, se registra otra vez. `ROLE_USER` crea y gestiona las propias; `ROLE_MANAGER` y `ROLE_ADMIN` también pueden revisar y gestionar reclamaciones ajenas. El permiso efectivo, además del rol, decide el acceso [C, S, M].

## 2. Ciclo de estados

| Estado actual | Significado | Acciones permitidas y destino | Quién puede cambiar estado | Acciones no permitidas |
|---|---|---|---|---|
| `DRAFT` | Borrador recién creado | Editar; enviar → `REGISTERED`; asignar responsable | Autor para enviar; revisor para enviar o asignar | Saltar directamente a revisión o decisión final |
| `REGISTERED` | Presentada para revisión | Revisor: revisar → `UNDER_REVIEW`; editar/asignar | Revisor | Autor: editar, revisar o decidir |
| `UNDER_REVIEW` | En evaluación | Revisor: → `ACCEPTED`, `REJECTED`, `PENDING_CORRECTION` o `INADMISSIBLE`; editar/asignar | Revisor | Autor: editar o decidir; transición a otros estados |
| `PENDING_CORRECTION` | Devuelta para corregir | Autor o revisor: editar y reenviar → `REGISTERED`; revisor: asignar | Autor para reenviar; revisor para reenviar o asignar | Decidirla directamente desde este estado |
| `ACCEPTED` | Decisión final favorable | Leer, comentar, ver historial y descargar adjuntos | Nadie cambia estado | Editar, asignar, subir/borrar adjuntos o salir del estado |
| `REJECTED` | Decisión final desfavorable | Igual que `ACCEPTED` | Nadie cambia estado | Igual que `ACCEPTED` |
| `INADMISSIBLE` | Decisión final de inadmisión | Igual que `ACCEPTED` | Nadie cambia estado | Igual que `ACCEPTED` |

**Revisor** significa usuario con capacidad `PERM_CLAIM_REVIEW`/`PERM_CLAIM_ADMIN` (en los roles de referencia, `MANAGER`/`ADMIN`). Puede ejecutar cualquier transición *legal*, también sobre una reclamación ajena; estar asignado no es requisito. El autor sin esa capacidad solo puede pasar su propia reclamación a `REGISTERED`. Repetir estado o solicitar una arista ausente da `409 INVALID_CLAIM_STATUS_TRANSITION`; una transición legal sin permiso da `403 ACCESS_DENIED`. **NO DETERMINADO:** los criterios sustantivos para escoger entre aceptación, rechazo, corrección e inadmisión; no hay regla automática ni motivo obligatorio en la petición [C, S, T].

## 3. Reglas de negocio importantes

| Regla | Condición → comportamiento esperado | Si se viola |
|---|---|---|
| Creación | `PERM_CLAIM_CREATE`; título y descripción no vacíos (máx. 200/4000); `claimantName` máx. 160. Se recortan espacios; estado `DRAFT`; prioridad omitida → `NORMAL` | `401` sin autenticación, `403` sin permiso, `400 VALIDATION_ERROR` con datos inválidos |
| Edición de datos | Autor: solo propia en `DRAFT`/`PENDING_CORRECTION`; revisor: cualquiera no final. Cambian título, descripción, nombre del reclamante, prioridad y plazo; `priority: null` conserva la existente, `dueAt: null` borra el plazo | Ajena no visible `404 CLAIM_NOT_FOUND`; final u otro estado no editable para autor `409 CLAIM_NOT_EDITABLE` |
| Decisión | Solo transiciones de la tabla con versión vigente; finales sin salida | `409 INVALID_CLAIM_STATUS_TRANSITION` o `403 ACCESS_DENIED` |
| Asignación | Actor revisor sobre cualquier estado no final; `assignedToId` obligatorio. El destino debe existir, estar habilitado y tener `ROLE_MANAGER`, `ROLE_ADMIN` o `PERM_CLAIM_REVIEW` | `400 INVALID_ASSIGNEE`; final `409 CLAIM_NOT_EDITABLE`; sin permiso `403` |
| Prioridad | `LOW`, `NORMAL`, `HIGH`, `CRITICAL`; se establece al crear o editar, no por transición automática | Enum inválido → `400 MALFORMED_REQUEST` |
| Versión | PUT de datos y PATCH de estado/asignación exigen `version` no negativa y vigente | Ausente/inválida `400 VALIDATION_ERROR`; obsoleta `409` (ver §9) |
| Comentarios | Cualquiera que pueda ver la reclamación puede comentar incluso si es final; texto no vacío, máx. 2000 | Ajena `404`; datos inválidos `400 VALIDATION_ERROR` |
| Cuenta utilizable | Deshabilitada, no verificada, bloqueada o con credenciales vencidas no inicia sesión; un bearer de cuenta no utilizable se rechaza | Login `403`/`423` según causa; bearer `401 INVALID_TOKEN` |

Los cambios de datos, prioridad, estado, asignación y adjuntos generan eventos de historial; los comentarios se consultan aparte. No hay operación de desasignación ni reapertura de estados finales en el contrato [C, D, H, M, S].

## 4. Permisos y ownership

| Actor de referencia | Lectura | Modificación y transición | Asignación | Adjuntos y recursos ajenos |
|---|---|---|---|---|
| `USER` | Solo reclamaciones creadas por él, con comentarios/historial/adjuntos | Crea; edita la propia en borrador/corrección; la envía o reenvía a `REGISTERED`; puede comentar las visibles | No | Lista/descarga los propios; sube/borra solo si su reclamación es editable. Una ajena devuelve `404` |
| `MANAGER` | Todas | Edita cualquier no final; ejecuta todas las transiciones legales; comenta visibles | Sí, a revisor elegible en no final | Lista/descarga cualquiera; sube/borra en cualquier no final |
| `ADMIN` | Igual que `MANAGER` | Igual que `MANAGER` | Igual | Igual; su rol incluye todos los permisos `PERM_CLAIM_*` |

**Autenticación**: bearer válido identifica al usuario (`401` si falta o falla). **Permiso**: anotación de endpoint exige `PERM_CLAIM_READ`, `CREATE`, `UPDATE` o `REVIEW`/`ADMIN` (`403` si falta). **Ownership**: para un usuario sin capacidad revisora, `createdBy.id` define lo propio; `assignedTo` no concede por sí solo lectura ni edición. El detalle de una reclamación ajena se oculta con `404`, y la búsqueda limita sus resultados a las propias. No hay endpoint de administración de usuarios en este contrato, aunque existan permisos `PERM_USER_*` en el esquema [C, S, M, U].

## 5. Endpoints principales

Base claims: `/api/v1/claims`. `R` = usuario con `PERM_CLAIM_READ` y reclamación visible; `E` = `PERM_CLAIM_UPDATE` y reclamación editable; `V` = capacidad revisora. Los `GET` devuelven `200`; las mutaciones indicadas devuelven JSON salvo `DELETE`/logout. Además de los errores indicados se aplican `401` (autenticación), `403` (permiso), `404` (recurso invisible/inexistente) y `400` por validación o petición mal formada [C, A, E].

| Grupo | Método | Ruta | Quién | Qué hace; precondición principal | Respuesta normal | Errores funcionales relevantes |
|---|---|---|---|---|---|---|
| Claims | `POST` | `/api/v1/claims` | `CREATE` | Crea borrador; datos válidos | `201 ClaimResponse`, `Location` | `400 VALIDATION_ERROR` |
| Claims | `GET` | `/api/v1/claims/{id}` | `R` | Lee detalle; visible | `200 ClaimResponse` | `404 CLAIM_NOT_FOUND` |
| Claims | `PUT` | `/api/v1/claims/{id}` | `E` | Actualiza datos; versión vigente | `200 ClaimResponse` | `409 CLAIM_NOT_EDITABLE`/`OPTIMISTIC_LOCK_CONFLICT` |
| Status | `PATCH` | `/api/v1/claims/{id}/status` | Autor o `V` | Cambia estado; transición legal y versión vigente | `200 ClaimResponse` | `403 ACCESS_DENIED`; `409 INVALID_CLAIM_STATUS_TRANSITION`/`OPTIMISTIC_LOCK_CONFLICT` |
| Assignment | `GET` | `/api/v1/claims/reviewers` | `V` | Lista revisores habilitados | `200 ReviewerResponse[]` | `403 ACCESS_DENIED` |
| Assignment | `PATCH` | `/api/v1/claims/{id}/assignment` | `V` | Asigna; no final, destino elegible y versión vigente | `200 ClaimResponse` | `400 INVALID_ASSIGNEE`; `409 CLAIM_NOT_EDITABLE`/`CLAIM_VERSION_CONFLICT` |
| Comments/history | `GET` | `/api/v1/claims/{id}/comments` | `R` | Lista comentarios; visible | `200 Comment[]` | `404 CLAIM_NOT_FOUND` |
| Comments/history | `GET` | `/api/v1/claims/{id}/history` | `R` | Lista eventos cronológicos; visible | `200 History[]` | `404 CLAIM_NOT_FOUND` |
| Comments/history | `POST` | `/api/v1/claims/{id}/comments` | `R` | Añade comentario válido, incluso en final | `201 Comment` | `400 VALIDATION_ERROR`; `404 CLAIM_NOT_FOUND` |
| Attachments | `GET` | `/api/v1/claims/{id}/attachments` | `R` | Lista metadatos; visible | `200 Attachment[]` | `404 CLAIM_NOT_FOUND` |
| Attachments | `GET` | `/api/v1/claims/{id}/attachments/capabilities` | `R` | Consulta límites activos; visible | `200 Capabilities` | `404 CLAIM_NOT_FOUND` |
| Attachments | `POST` | `/api/v1/claims/{id}/attachments` | `E` | Sube multipart `files`; `relativePaths` opcional | `201 Attachment[]` | `400 ATTACHMENT_REQUIRED`/`INVALID_ATTACHMENT_PATH`/`ATTACHMENT_LIMIT_EXCEEDED`; `413`; `415 ATTACHMENT_TYPE_NOT_ALLOWED`; `409 ATTACHMENT_CONFLICT` |
| Attachments | `GET` | `/api/v1/claims/{id}/attachments/{attachmentId}/content` | `R` | Descarga; ID perteneciente a esa Claim | `200` binario | `404 ATTACHMENT_NOT_FOUND` o `CLAIM_NOT_FOUND` |
| Attachments | `DELETE` | `/api/v1/claims/{id}/attachments/{attachmentId}` | `E` | Borra; ID perteneciente a esa Claim | `204` | `404 ATTACHMENT_NOT_FOUND`; `409 CLAIM_NOT_EDITABLE` |
| Búsqueda | `GET` | `/api/v1/claims` | `READ` | Filtra, pagina y ordena; alcance §4 y filtros §7 | `200 PageResponse<ClaimSummary>` | `400 INVALID_DATE_RANGE`/`INVALID_PAGE_REQUEST`/`INVALID_SORT_FIELD`/`INVALID_SORT_DIRECTION` |
| Auth/users | `POST` | `/api/auth/login` | Público | Autentica; credenciales y cuenta válidas | `200` tokens + perfil | `401 BAD_CREDENTIALS`; cuenta no utilizable `403`/`423` |
| Auth/users | `POST` | `/api/auth/refresh` | Público | Rota refresh válido | `200` tokens + perfil | `401 INVALID_REFRESH_TOKEN`/`REFRESH_TOKEN_EXPIRED` |
| Auth/users | `POST` | `/api/auth/logout` | Público | Revoca refresh indicado o los propios si hay bearer | `204` | — |
| Auth/users | `GET` | `/api/auth/me` | Autenticado | Lee perfil, roles y permisos | `200 UserProfileResponse` | `401` sin token |

## 6. Campos importantes de Claim

| Campo | Significado y momento de cambio |
|---|---|
| `id` | Identificador numérico interno; asignado al insertar; estable |
| `reference` | Código único `CLM-año-secuencia`, generado en DB al crear; estable |
| `title`, `description` | Datos obligatorios; se fijan al crear y cambian con PUT permitido |
| `status` | Uno de los siete estados de §2; inicia en `DRAFT`; solo PATCH de estado |
| `priority` | Urgencia `LOW`/`NORMAL`/`HIGH`/`CRITICAL`; inicial `NORMAL` si omitida; cambia con PUT explícito |
| `assignedTo` | Usuario responsable de revisión o `null`; cambia con PATCH de asignación, no transfiere `createdBy` |
| `createdBy` | Autor/propietario; fijado al crear, estable |
| `createdAt` | Instante de creación; estable |
| `updatedAt` | Última actualización de la fila de Claim; edición, estado o asignación lo modifican; la DB también lo fija mediante trigger en un UPDATE |
| `dueAt` | Plazo opcional; lo fija creación/PUT, `null` en PUT lo borra; no cambia automáticamente al cerrar |
| `version` | Revisión optimista de la fila Claim; empieza en 0 y avanza cuando esta se actualiza. Comentarios y metadatos de adjuntos no la incrementan por sí mismos |

`claimantName` es el nombre opcional del reclamante; no sustituye a `createdBy`. `assignedAt` fecha la asignación y `updatedBy` identifica al actor del último cambio de Claim [D, H, M].

## 7. Filtros y búsquedas

Todos se combinan con **AND**, después de aplicar el alcance de ownership. Filtro ausente = sin restricción. `search` busca texto parcial sin distinguir mayúsculas en referencia, título, descripción, nombre del reclamante y usuario/email creador [C, F, S].

| Filtro | Significado esperado |
|---|---|
| `reference` | Contiene texto, sin distinguir mayúsculas |
| `status`, `priority` | Igualdad exacta con sus enums |
| `assignedTo`, `createdBy` | Contiene texto en username o email, sin distinguir mayúsculas; si el valor es numérico también admite coincidencia exacta con ID |
| `createdFrom` | `createdAt >= YYYY-MM-DD 00:00:00 UTC` (incluido) |
| `createdTo` | `createdAt < 00:00:00 UTC` del día **siguiente** (incluye todo el día indicado) |
| `overdue=true` | `dueAt` presente y estrictamente anterior al instante actual; estado no final |
| `overdue=false` | `dueAt` ausente o mayor/igual al instante actual; una finalizada con plazo pasado no entra ni en `true` ni en `false` |

`createdFrom > createdTo` → `400 INVALID_DATE_RANGE`. `page` empieza en 0 (por defecto 0), `size` por defecto 20 y se limita a 100; orden por defecto `createdAt,desc`, con campos `reference`, `title`, `status`, `priority`, `dueAt`, `createdAt`, `updatedAt`, `createdBy` y dirección `asc`/`desc` [C, F].

## 8. Attachments

Un usuario con edición sobre la Claim puede subir o borrar adjuntos mientras no esté finalizada; quien pueda verla puede listar metadatos, consultar capacidades y descargar, también después del cierre. Se admiten archivos genéricos y rutas relativas para carpetas; no hay lista general de extensiones. Para PDF, imágenes y varios formatos comprimidos/documentales conocidos se exige que la firma del contenido concuerde con la extensión (`415` si no). Las rutas se normalizan y se rechazan rutas absolutas, traversal o caracteres de control; nombres repetidos en una Claim reciben sufijo. Límites por defecto configurables: **25 MB por archivo, 500 MB por solicitud, 20 archivos** [A, P].

La DB guarda metadatos ligados a `claim_id` (UUID, ruta relativa, tipo, tamaño, SHA-256, autor y clave de almacenamiento); el binario reside en filesystem. Descargar exige que el UUID pertenezca a la Claim indicada y que exista el fichero; si falta, `404 ATTACHMENT_NOT_FOUND`. Un upload múltiple es una única operación: si falla cualquier archivo, no deben quedar metadatos confirmados de ese lote y se intenta limpiar los binarios ya escritos. El almacenamiento limpia también un archivo parcialmente escrito. Borrar confirma primero la eliminación de metadatos y después intenta borrar el fichero; **la limpieza física es de mejor esfuerzo**, por lo que un fallo de I/O o del proceso puede dejar un fichero huérfano aunque la respuesta sea `204`. No existe atomicidad absoluta entre DB y filesystem [A, P, X].

## 9. Concurrencia / `version`

El cliente envía la versión recibida con PUT de datos, PATCH de estado o PATCH de asignación. Cada actualización confirmada de la fila Claim incrementa su versión; una petición obsoleta no debe sobrescribir datos ni confirmar su historial y debe recargar antes de reintentar. Para datos/estado el rechazo previo es `409 OPTIMISTIC_LOCK_CONFLICT`; para asignación es `409 CLAIM_VERSION_CONFLICT`. Si dos peticiones pasan el chequeo a la vez, el bloqueo optimista de DB produce `409 OPTIMISTIC_LOCK_CONFLICT` al confirmar una de ellas. Subidas y borrados de adjuntos bloquean la Claim durante la operación para serializar escrituras sobre su colección; no usan `version` del cliente. Comentarios y adjuntos no actualizan por sí solos la versión de Claim [H, S, X].

## 10. Mapa de debugging

| Síntoma | Mirar primero |
|---|---|
| Estado o transición inesperada | Tabla §2 → autorización del endpoint → reglas de transición del servicio |
| Búsqueda devuelve filas erróneas | Alcance por autor → filtros/Specification → límites UTC de fechas → SQL |
| Campo cambia o se conserva inesperadamente | DTO → mapeo a entidad → `@Version`/dirty checking → trigger `updated_at` |
| `401`/`403`/`404` extraño | Bearer y estado de cuenta → permiso del endpoint → `createdBy`/visibilidad → asociación recurso–Claim |
| `409` tras editar o asignar | Versión enviada vs recibida → transición/estado final → concurrencia en DB |
| Metadatos correctos pero archivo ausente/sobrante | Transacción y callbacks de limpieza → storage local → rutas/clave |

## Ambigüedad

- **AMBIGÜEDAD:** `overdue=false` tiene la fórmula exacta de §7, pero no hay prueba específica ni regla de producto que confirme si se pretendía que fuera el complemento lógico de `true`. No se infiere otra semántica.
- **NO DETERMINADO:** los criterios de negocio para elegir una decisión final o solicitar corrección; el contrato solo delimita estados y permisos.

## Rastreo de fuentes

Claves de la revisión `main@a89ace7c6d16`; son rutas de referencia, no de la copia de trabajo. Los nombres de funciones permiten localizar reglas cuando se cita un módulo sin archivo:

- **[C]** `src/main/java/com/mendez/ram/claim/controller/ClaimController.java` (rutas, permisos, filtros, paginación); `src/test/java/com/mendez/ram/claim/ClaimControllerIntegrationTest.java` (respuestas y flujo).
- **[S]** `src/main/java/com/mendez/ram/claim/service/ClaimService.java` (ownership, reglas, errores, historial); `src/test/java/com/mendez/ram/claim/ClaimServiceTest.java`.
- **[T]** `src/main/java/com/mendez/ram/claim/entity/` — buscar `canTransitionTo`, `isFinal`, `isEditableByOwner`; `src/main/resources/db/migration/V5__evolve_claims_for_v1_api.sql` (estados y permisos).
- **[D]** `src/main/java/com/mendez/ram/claim/dto/` (`CreateClaimRequest`, `UpdateClaimRequest`, `ChangeClaimStatusRequest`, `AssignClaimRequest`, `ClaimResponse`); `src/main/java/com/mendez/ram/claim/mapper/` — buscar `updateClaimEntity`.
- **[F]** `src/main/java/com/mendez/ram/claim/repository/` — buscar `matchingClaimSearchCriteria`, `createdOnOrBefore`, `overdueClaims`.
- **[A]** `src/main/java/com/mendez/ram/attachment/controller/AttachmentController.java`; `src/main/java/com/mendez/ram/attachment/service/` — buscar `uploadClaimAttachments`, `findAttachmentByClaimAndId`; `src/main/resources/db/migration/V6__create_claim_attachments.sql`.
- **[P]** `src/main/java/com/mendez/ram/attachment/config/AttachmentProperties.java`, `src/main/java/com/mendez/ram/attachment/storage/LocalAttachmentStorage.java`, `src/main/resources/application.properties`.
- **[X]** `src/main/java/com/mendez/ram/attachment/service/` — buscar `registerUploadRollbackCleanup`, `deleteStorageAfterCommit`; `src/main/java/com/mendez/ram/claim/repository/ClaimRepository.java` (bloqueo).
- **[H]** `src/main/java/com/mendez/ram/claim/entity/Claim.java` (`@Version`, campos); `src/main/resources/db/migration/V4__create_claims.sql` y `V7__claim_workflow.sql`.
- **[M]** `src/main/resources/db/migration/V5__evolve_claims_for_v1_api.sql`; `src/main/resources/db/migration/V3__seed_security_roles_permissions.sql`.
- **[U]** `src/main/java/com/mendez/ram/auth/controller/AuthController.java`, `src/main/java/com/mendez/ram/auth/service/AuthService.java`, `src/main/java/com/mendez/ram/security/SecurityConfig.java`, `src/main/java/com/mendez/ram/security/JwtAuthenticationFilter.java`.
- **[E]** `src/main/java/com/mendez/ram/exception/GlobalExceptionHandler.java`.
