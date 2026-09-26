HECHO
{
## Bug 1 — Dos ediciones aceptadas -- nunca existió

Contexto:
Una reclamación editable ya ha sido modificada por el usuario que realizará ambas ediciones. Dos clientes de ese mismo usuario consultan el detalle y conservan la misma versión y los mismos datos.

Acción:
En el primer cliente, envía `PUT /api/v1/claims/{id}` cambiando únicamente `dueAt` y conservando el resto del contenido. Después de recibir la respuesta, envía desde el segundo cliente otra edición con la versión y la fecha límite originales, cambiando el título. Consulta de nuevo el detalle.

Esperado:
La segunda edición recibe un conflicto porque parte de una lectura anterior a una modificación confirmada. La fecha límite del primer cliente permanece guardada.

Observado:
Las dos ediciones se aceptan. La segunda puede restaurar la fecha límite antigua.

}
HECHO
{
1. Bug 2 — Un refresh de un solo uso genera dos sucesores
Contexto: Haz login y conserva un refresh1 válido que todavía no hayas usado.
Prepara dos peticiones independientes A y B con exactamente ese mismo refresh1.
Acción: Lanza A y B de forma concurrente, no una después de otra:
refresh1
/          \
A            B
POST /refresh   POST /refresh

Esperado: Solo una petición puede consumir refresh1.
A → 200 → refresh2
B → 401

o al revés. Después, el nuevo refresh del ganador debe poder rotarse normalmente y refresh1 debe quedar muerto.
Bug confirmado únicamente si:
A → 200 → refresh2
B → 200 → refresh3

y, especialmente, si después:
refresh2 → 200
refresh3 → 200

Eso demostraría que un token que debía consumirse una sola vez produjo dos ramas válidas de sesión.
Importante para tu checkout actual: este sí fue encontrado por Codex,
pero posteriormente restauró un bloqueo pesimista en el repositorio de refresh,
así que puede que ahora obtengas correctamente 200 + 401.   Texto pegado
}
HECHO
{
2. Bug 3 — La operación falla, pero parte del cambio queda guardado
   Contexto: Necesitas una Claim no finalizada,
   un usuario autorizado para asignarla y un usuario válido al que asignársela.
   Apunta antes de empezar quién es el assignedTo actual y revisa su historial.
   El escenario necesita que durante la operación falle la escritura del historial,
   mientras el cambio principal de la Claim sí alcanza la base de datos.
   El ejercicio preparado por Codex usa una condición aislada de prueba
   para provocar ese fallo; no es simplemente enviar datos inválidos.
   Acción: Ejecuta la asignación. La API debe terminar devolviendo error.
   Después quita la condición que provocaba el fallo y, mediante peticiones nuevas, consulta:
   detalle de Claim
   +
   historial de Claim

   Esperado: La operación es una unidad indivisible.
   PATCH asignación → ERROR
   después:
   assignedTo = valor anterior
   historial  = sin nueva asignación

   Si algo falla en esa operación, no debería persistirse ninguna mitad.
   Bug confirmado únicamente si:
   PATCH asignación → ERROR

   después:
   assignedTo = NUEVO usuario ❌
   historial  = NO contiene la asignación

   Es decir: el cliente recibió “la operación falló”, pero al volver a consultar descubre que sí cambió el estado del dominio. No busques primero la causa: primero demuestra esa contradicción.
   }
   HECHO
   {
3. Bug 4 — La primera página parece perfecta y la segunda pierde resultados
   Contexto: Crea o localiza al menos 5 Claims visibles para el mismo usuario,
   todas coincidiendo con una misma búsqueda, pero con títulos distintos y fáciles de ordenar.
   No modifiques datos durante la prueba.
   Ejemplo conceptual:
   BUG PAGINATION A
   BUG PAGINATION B
   BUG PAGINATION C
   BUG PAGINATION D
   BUG PAGINATION E

   Haz primero una consulta grande que te sirva como verdad de referencia:
   search = término común
   sort   = title ASC
   size   = suficientemente grande           :

   {
   "content": [
   {
   "id": 32,
   "reference": "CLM-2026-000032",
   "title": "Reclamación de prueba #32",
   "status": "ACCEPTED",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-05T02:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 33,
   "reference": "CLM-2026-000033",
   "title": "Reclamación de prueba #33",
   "status": "REJECTED",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-05T01:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 34,
   "reference": "CLM-2026-000034",
   "title": "Reclamación de prueba #34",
   "status": "INADMISSIBLE",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-05T00:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 35,
   "reference": "CLM-2026-000035",
   "title": "Reclamación de prueba #35",
   "status": "DRAFT",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T23:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 36,
   "reference": "CLM-2026-000036",
   "title": "Reclamación de prueba #36",
   "status": "PENDING_CORRECTION",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": 3,
   "assignedToUsername": "dev-admin",
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T22:56:44.200976Z",
   "updatedAt": "2026-09-23T02:56:55.724885Z"
   },
   {
   "id": 37,
   "reference": "CLM-2026-000037",
   "title": "Reclamación de prueba #37",
   "status": "UNDER_REVIEW",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T21:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 38,
   "reference": "CLM-2026-000038",
   "title": "Reclamación de prueba #38",
   "status": "PENDING_CORRECTION",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T20:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 39,
   "reference": "CLM-2026-000039",
   "title": "Reclamación de prueba #39",
   "status": "ACCEPTED",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T19:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 40,
   "reference": "CLM-2026-000040",
   "title": "Reclamación de prueba #40",
   "status": "REJECTED",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T18:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 41,
   "reference": "CLM-2026-000041",
   "title": "Reclamación de prueba #41",
   "status": "INADMISSIBLE",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T17:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 42,
   "reference": "CLM-2026-000042",
   "title": "Reclamación de prueba #42",
   "status": "DRAFT",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T16:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 43,
   "reference": "CLM-2026-000043",
   "title": "Reclamación de prueba #43",
   "status": "REGISTERED",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T15:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 44,
   "reference": "CLM-2026-000044",
   "title": "Reclamación de prueba #44",
   "status": "UNDER_REVIEW",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T14:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 45,
   "reference": "CLM-2026-000045",
   "title": "Reclamación de prueba #45",
   "status": "PENDING_CORRECTION",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T13:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 46,
   "reference": "CLM-2026-000046",
   "title": "Reclamación de prueba #46",
   "status": "ACCEPTED",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T12:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 47,
   "reference": "CLM-2026-000047",
   "title": "Reclamación de prueba #47",
   "status": "REJECTED",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T11:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   },
   {
   "id": 48,
   "reference": "CLM-2026-000048",
   "title": "Reclamación de prueba #48",
   "status": "INADMISSIBLE",
   "priority": "NORMAL",
   "dueAt": null,
   "assignedToId": null,
   "assignedToUsername": null,
   "createdById": 3,
   "createdByUsername": "dev-admin",
   "createdAt": "2026-09-04T10:56:44.200976Z",
   "updatedAt": "2026-09-23T02:42:58.604120Z"
   }
   ],
   "page": 0,
   "size": 20,
   "totalElements": 17,
   "totalPages": 1,
   "first": true,
   "last": true
   }

   Debes ver los cinco y conocer su orden.
   Acción: Repite exactamente la misma búsqueda y ordenación, pero paginando:
   page=0 size=2
   page=1 size=2
   {
   "content": [],
   "page": 1,
   "size": 2,
   "totalElements": 17,
   "totalPages": 9,
   "first": false,
   "last": false
   }

   page=2 size=2
   {
   "content": [],
   "page": 2,
   "size": 2,
   "totalElements": 17,
   "totalPages": 9,
   "first": false,
   "last": false
   }

   Esperado:
   page 0 → A, B
   page 1 → C, D
   page 2 → E

   totalElements = 5 en todas

   No debe haber duplicados ni Claims desaparecidas.
   Bug confirmado únicamente si la primera página parece correcta pero una posterior hace algo como:
   page 0 → 2 elementos ✅
   totalElements → 5

   page 1 → [] ❌
   totalElements → sigue diciendo 5

   También cuenta como reproducción si aparecen duplicados u omisiones al comparar todas las páginas contra la consulta grande.
   La clave del ejercicio es precisamente que mirar únicamente page=0 puede hacerte creer que todo funciona.
   }
   POR HACER
   {

4. Bug 5 — Dos decisiones válidas por separado producen un estado imposible juntas
   Contexto: Parte de una Claim en:
   UNDER_REVIEW

   y dos reviewers autorizados. Ambos deben haber leído la Claim cuando tenía la misma versión inicial V.
   Prepara dos operaciones distintas que desde UNDER_REVIEW son individualmente válidas:
   A → ACCEPTED
   B → PENDING_CORRECTION

   Primero haz un pequeño control secuencial en otra Claim: acepta primero y después intenta pasarla a PENDING_CORRECTION. Esa segunda operación debería ser rechazada. Eso fija la regla de dominio que estás intentando proteger.
   Acción real: En la Claim del ejercicio, ejecuta A y B concurrentemente partiendo de la misma versión V.
   Esperado: Solo una decisión debe conseguir consolidarse.
   Por ejemplo:
   A → 200
   B → conflicto

   o al revés.
   El historial y el estado final deben reflejar una sola decisión compatible.
   Bug confirmado únicamente si:
   A → 2xx
   B → 2xx

   y después encuentras una combinación imposible, especialmente:
   ACCEPTED ocurrió
   ↓
   estado final = PENDING_CORRECTION ❌

   Lo importante aquí no es simplemente “dos requests a la vez”. Es demostrar que la concurrencia permite una secuencia que ejecutada normalmente una detrás de otra estaría prohibida.
   }
   POR HACER
   {

5. Bug 6 — Tiene permiso para revisar Claims, pero no para obtener los reviewers
   Contexto: Necesitas un usuario activo cuyo rol personalizado tenga:
   PERM_CLAIM_READ
   PERM_CLAIM_REVIEW

   pero que NO tenga:
   ROLE_MANAGER
   ROLE_ADMIN

   Después de configurar permisos, inicia una sesión nueva para no trabajar con un JWT anterior.
   Acción: Con el mismo JWT comprueba primero dos cosas:
   leer una Claim → permitido
   ejecutar una operación real de revisión → permitido

   Eso demuestra que sus permisos de review son reales.
   Después llama:
   GET /api/v1/claims/reviewers

   Esperado: Si el contrato de autorización está basado en PERM_CLAIM_REVIEW, ese mismo usuario debería poder obtener los reviewers necesarios para realizar el trabajo que ya tiene permitido hacer.
   Bug confirmado únicamente si obtienes esta contradicción:
   leer Claim               → 200
   operación de review      → 2xx
   GET /claims/reviewers    → 403 ❌

   No necesitas todavía saber si el fallo está en JWT, authorities, seguridad del endpoint o servicio. La contradicción observable es el ejercicio.
   }
   POR HACER
   {

6. Bug 7 — El DELETE falla, la base de datos hace rollback… pero el fichero ha desaparecido
   Contexto: Usa una Claim editable que tenga un attachment real.
   Antes de tocar nada confirma:
   listado de attachments → aparece
   descarga               → funciona

   Guarda el ID del attachment.
   El escenario preparado necesita provocar un fallo al final de la transacción de base de datos, después de que la operación de borrado haya avanzado. La condición de prueba está diseñada para hacer que el commit sea rechazado; no basta con enviar un ID inexistente o provocar un 400 temprano.
   Acción: Ejecuta el DELETE. Debe responder con error. Después elimina la condición de fallo y haz peticiones completamente nuevas:
   GET/list attachment
   GET/download attachment

   Esperado: Si el DELETE no pudo hacer commit:
   DB rollback
   attachment sigue listado
   fichero sigue descargable

   Desde fuera debe parecer que el DELETE nunca ocurrió.
   Bug confirmado únicamente si:
   DELETE → ERROR

   nueva consulta:
   attachment sigue en la lista ✅

   nueva descarga:
   contenido ya no existe / descarga falla ❌

   Esa combinación es la señal fuerte:
   BASE DE DATOS: "el attachment existe"
   ALMACENAMIENTO: "el attachment no existe"

   No es el mismo bug del multipart anterior: aquí estás comprobando qué ocurre con un efecto externo cuando la transacción de DB finalmente no se confirma.
   Esto habría sido una documentación de entrenamiento mucho más útil: te dice cómo montar cada experimento y qué observación demuestra el defecto, pero sigue sin decirte clase, método, línea, causa raíz ni arreglo.
   Y desde ahora aplicaría una regla estricta con esta ronda: si obtienes el comportamiento “Esperado” de forma limpia, no declaramos que “no sabes encontrar el bug”; marcamos NO REPRODUCIDO y comprobamos el diff antes de quemar una hora como con Bug 1.
   }
