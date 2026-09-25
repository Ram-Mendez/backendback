## Bug 1 — Dos ediciones aceptadas

Contexto:
Una reclamación editable ya ha sido modificada por el usuario que realizará ambas ediciones. Dos clientes de ese mismo usuario consultan el detalle y conservan la misma versión y los mismos datos.

Acción:
En el primer cliente, envía `PUT /api/v1/claims/{id}` cambiando únicamente `dueAt` y conservando el resto del contenido. Después de recibir la respuesta, envía desde el segundo cliente otra edición con la versión y la fecha límite originales, cambiando el título. Consulta de nuevo el detalle.

Esperado:
La segunda edición recibe un conflicto porque parte de una lectura anterior a una modificación confirmada. La fecha límite del primer cliente permanece guardada.

Observado:
Las dos ediciones se aceptan. La segunda puede restaurar la fecha límite antigua.

## Bug 2 — Una renovación, dos sucesores

Contexto:
Una sesión dispone de un refresh token válido que todavía no se ha utilizado.

Acción:
Envía simultáneamente dos `POST /api/auth/refresh` con ese mismo token, desde dos clientes o un test concurrente. Si ambas respuestas son satisfactorias, intenta renovar por separado con cada token recibido. Repite con sesiones nuevas si las peticiones no llegan a solaparse.

Esperado:
Solo una petición consume el token original. La otra se rechaza y no genera un segundo token utilizable.

Observado:
Ambas peticiones pueden tener éxito y entregar dos refresh tokens distintos que siguen siendo utilizables.

## Bug 3 — Asignación sin rastro

Contexto:
Un revisor autorizado dispone de una reclamación no finalizada, su versión actual y un responsable elegible. En un entorno de pruebas aislado, prepara un fallo controlado que rechace la escritura del historial de esa asignación, sin impedir otras escrituras.

Acción:
Envía `PATCH /api/v1/claims/{id}/assignment` con `assignedToId` y `version`. Tras el error, retira la condición de fallo y consulta el detalle y `GET /api/v1/claims/{id}/history` desde peticiones nuevas.

Esperado:
La operación fallida conserva el responsable anterior y no registra una asignación parcial.

Observado:
El nuevo responsable permanece guardado aunque falta el evento de asignación correspondiente.

## Bug 4 — Resultados que desaparecen

Contexto:
Existen al menos cinco reclamaciones visibles para el usuario, con títulos distintos que comparten un texto identificable. No hay modificaciones concurrentes durante la consulta.

Acción:
Consulta `GET /api/v1/claims?search={texto}&sort=title,asc&size=2&page=0` y después la misma búsqueda con `page=1`. Compara los elementos devueltos, el total y una consulta con tamaño suficiente para incluir todas las coincidencias.

Esperado:
La segunda página contiene las dos coincidencias siguientes en el orden solicitado, sin omisiones, y el total describe el conjunto completo.

Observado:
La primera página parece correcta, pero la segunda puede aparecer vacía aunque el total indique que quedan coincidencias.

## Bug 5 — Dos decisiones incompatibles

Contexto:
Una reclamación está en `UNDER_REVIEW`. Dos revisores autorizados consultan el detalle y conservan la misma versión.

Acción:
Envía concurrentemente dos `PATCH /api/v1/claims/{id}/status`, uno solicitando `ACCEPTED` y otro `PENDING_CORRECTION`, ambos con la versión consultada. Usa un test concurrente o peticiones solapadas; después consulta el detalle y el historial. Como control, repite las decisiones secuencialmente sobre otra reclamación, aceptándola primero.

Esperado:
Solo una decisión concurrente se confirma. La otra recibe un conflicto. Una reclamación aceptada no vuelve a quedar pendiente de corrección.

Observado:
Las dos decisiones concurrentes pueden responder satisfactoriamente. Según el orden efectivo, una reclamación aceptada termina en `PENDING_CORRECTION`. El control secuencial rechaza la segunda decisión.

## Bug 6 — Revisor sin acceso al listado

Contexto:
Un usuario activo tiene un rol personalizado que concede `PERM_CLAIM_READ` y `PERM_CLAIM_REVIEW`, sin pertenecer a `ROLE_MANAGER` ni a `ROLE_ADMIN`. Inicia una sesión nueva después de configurar estos permisos.

Acción:
Con el JWT de esa sesión, consulta una reclamación y realiza una operación de revisión válida. Después solicita `GET /api/v1/claims/reviewers`.

Esperado:
El permiso de revisión permite consultar los responsables elegibles, igual que permite realizar las operaciones de revisión correspondientes.

Observado:
El listado devuelve `403` aunque ese usuario puede realizar otras operaciones de revisión.

## Bug 7 — Borrado rechazado, descarga perdida

Contexto:
Una reclamación editable tiene un adjunto que se lista y descarga correctamente. En un entorno aislado, prepara una condición de base de datos que permita ejecutar el borrado pero rechace la confirmación final de esa transacción mediante una validación diferida.

Acción:
Solicita `DELETE /api/v1/claims/{claimId}/attachments/{attachmentId}`. Después del error, retira la condición de fallo y, desde peticiones nuevas, vuelve a listar y descargar el adjunto.

Esperado:
Como el borrado no se confirmó, el adjunto sigue disponible tanto en el listado como en la descarga.

Observado:
El adjunto sigue apareciendo en el listado, pero su contenido ya no se puede descargar.
