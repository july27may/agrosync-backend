# Validación del backend

Verificado el 19 de noviembre de 2026. El contenedor `agrosync-backend-backend-1` quedó en ejecución y publica `127.0.0.1:8081`. Se conecta a `app:80` por `suitecrm810_default`.

## SuiteCRM real

- El endpoint OAuth2 devolvió HTTP 200 con un token nuevo y una vigencia de 3600 segundos al recibir un formulario con `client_credentials`.
- Los metadatos de `ags_Fincas` devolvieron los nueve campos esperados. La lista y el detalle JSON:API devolvieron los ocho atributos solicitados. El ID se recibió en `data.id`.
- El CRM devolvió `area_ha` como texto decimal y `estado` como `Activa`. Un ID inexistente produjo HTTP 400 en el CRM con un detalle que identifica el registro faltante.
- Se comprobó el recorrido completo a través del backend mediante HTTP.

| Solicitud al backend | Resultado observado |
|---|---|
| `GET /actuator/health` | 200, `{"status":"UP"}` |
| `GET /api/v1/fincas?page=1&size=20` | 200, una finca en una página |
| `GET /api/v1/fincas/{id de La Quiebra}` | 200, **La Quiebra**, `FIN-001`, `Activa`, `areaHa: 10.00000000` |
| `GET /api/v1/fincas?page=2&size=20` | 200, lista vacía y `totalPages: null` |
| `GET /api/v1/fincas/{id inexistente}` | 404, `FINCA_NO_ENCONTRADA` |
| `GET /api/v1/fincas?page=0` | 400, `SOLICITUD_INVALIDA` |

## Simulación y compilación

`mvn verify` con Java 21 compiló el JAR ejecutable y pasó **13 pruebas**. El Dockerfile también ejecuta `mvn verify` al construir la imagen.

- Diez pruebas con un servidor HTTP local simulado cubren el formulario OAuth, la reutilización, la renovación anticipada, las lecturas concurrentes, el reintento ante 401 y su límite, la paginación, un registro inexistente, los opcionales vacíos, la precisión decimal, la conexión rechazada y el timeout.
- Tres pruebas con MockMvc cubren Actuator, las entradas de paginación inválidas y la respuesta segura cuando faltan credenciales.
- La expiración, el 401 y los fallos de red se provocaron en el simulador. Queda pendiente observar esos escenarios en una prueba controlada del CRM real.

## Refactorización por funcionalidad

`mvn clean verify` pasó las 13 pruebas después de reorganizar los paquetes. Se reconstruyó y arrancó el contenedor desde el proyecto independiente. Se comprobaron Actuator, las claves JSON de lista y detalle, la consulta de La Quiebra y el error 404 de finca inexistente contra SuiteCRM real.

## Catálogos para captura de café

Se reconstruyó el backend y se ejecutaron 17 pruebas JUnit. Las nuevas pruebas cubren el filtro `finca_id`, el rechazo de lotes de otra finca, la paginación, el mapeo de los nombres de Contacts, la reutilización del token y el origen permitido por CORS. SuiteCRM confirmó los campos de `ags_lotes` y `Contacts`. La consulta real de lotes de La Quiebra devolvió una página vacía.

## Autenticación y autorización

Pasaron **21 pruebas JUnit**. Spring Security bloqueó el acceso anónimo, rechazó credenciales incorrectas y peticiones POST sin CSRF, permitió iniciar y cerrar sesión y comprobó los permisos de finca. La prueba con rol Consulta no pudo abrir recursos de otra finca. Una cuenta sin asignaciones no accedió a Contactos ni obtuvo autorización offline.

Un contenedor aislado con una cuenta temporal de registrador probó la API contra SuiteCRM real. Se verificaron el login, la cookie HttpOnly y SameSite Strict, el rechazo de credenciales inválidas, La Quiebra como única finca asignada, el 403 para otra finca, la firma ECDSA y vigencia de 24 horas del permiso offline y el cierre de sesión. La cuenta temporal se retiró al finalizar.

## Migración de identidad a SuiteCRM

- La configuración de cuentas locales se retiró del código y de Compose. Las pruebas actuales usan un servidor OAuth/API simulado para validar PKCE, consumo único de state, sesión Spring Security, permisos distintos por finca, revocación de asignaciones en una sesión vigente, CSRF y cierre. Pasaron 17 pruebas Java.
- En Chromium se verificó la navegación desde React al formulario de ingreso real de SuiteCRM. La prueba integrada simuló el retorno autenticado, preparó el permiso por finca, confirmó que Consulta queda fuera del formulario de captura, reabrió sin conexión y comprobó que el vencimiento conserva los pendientes.
- SuiteCRM real validó sintaxis PHP, respondió 401 a un acceso anónimo a la ruta de permisos, 403 al cliente técnico por esa ruta y siguió devolviendo La Quiebra con dicho cliente.
- Pendiente completar un login real con una persona autorizada y comprobar dos perfiles asignados en fincas distintas. La instalación aún no tiene membresías AgroSync aprobadas. No se crearon cuentas ni se modificaron contraseñas.

## Cierre de identidad y autorización offline

| Caso | Evidencia |
|---|---|
| Existencia y estado del usuario real | SuiteCRM tiene un Usuario activo y habilitado según su verificación nativa. Java requiere el identificador procedente de `current-user` y la ruta de autorización exige `Active` e `isEnabled()`. |
| Contraseña incorrecta | El formulario real de SuiteCRM rechazó un único intento contra la cuenta activa con HTTP 401. No se usó el cliente técnico como identidad. |
| Usuario válido con contraseña correcta | La navegación al formulario real está comprobada. Pendiente completar el retorno autenticado con el operador. |
| Usuario inactivo | Pendiente prueba funcional. No hay cuentas inactivas en la instancia. Se verificó la condición de rechazo en el código de SuiteCRM y de la ruta personalizada. |
| Consulta intentando registrar | La prueba del navegador, con catálogos y autorización de prueba, mostró que la finca con perfil Consulta queda fuera del selector de captura. Pendiente repetir con una cuenta real asignada. |
| Acceso a otra finca | Las pruebas Java verificaron HTTP 403 para una finca y sus lotes fuera del alcance, con el proveedor HTTP simulado. Pendiente la prueba con una cuenta y grupos reales. |
| Vencimiento y retorno en línea | El backend firmó una vigencia de 90 minutos en la prueba de configuración. Chromium comprobó que reabrir sin red no cambia el permiso, adelantó el reloj, comprobó el bloqueo y recuperó los mismos pendientes al reanudar sesión con permisos verificados por el proveedor de prueba. |
| Sincronización | Las operaciones siguen pendientes. No se envió ninguna recolección a SuiteCRM en estas pruebas. |

La instalación local no tiene membresías de usuarios en grupos de AgroSync ni roles asociados al grupo de La Quiebra. Ningún usuario nuevo fue creado ni se modificó una contraseña. Pasaron 17 pruebas Java y la prueba offline de Chromium. En el entorno de uso se configuró `AGROSYNC_OFFLINE_TTL=24h`, pendiente de acordar con la finca.

## Repetición de la auditoría de origen y casos reales

Comprobación del 19 de noviembre de 2026. Se revisaron el código Java de producción y el contenedor en ejecución. No existe un proveedor de cuentas locales ni un montaje de `accounts/accounts.json`. El contenedor reportó `AGROSYNC_OFFLINE_TTL=24h`.

| Caso | Tipo | Resultado |
|---|---|---|
| Usuario existente y contraseña incorrecta | SuiteCRM real, formulario en Chromium | Un intento de repetición contra la única cuenta activa devolvió **401**. No se creó ninguna cuenta ni se cambió ninguna contraseña. |
| Ingreso correcto | SuiteCRM real, parcial | La cuenta existente tiene estado de usuario y de empleado activos. La navegación de React al formulario de SuiteCRM está comprobada. Falta que su titular complete el ingreso y confirme el callback. |
| Inactividad | SuiteCRM real, inventario y código | No hay cuentas inactivas. Se confirmó la validación nativa de usuario habilitado y la exigencia adicional de `Active` en la ruta AgroSync. Falta probar con una cuenta real inactiva autorizada para la prueba. |
| Perfil Consulta y finca ajena | Pruebas automatizadas con proveedor simulado | Chromium excluyó del formulario la finca con perfil Consulta. Java devolvió **403** para el detalle de una finca y el catálogo de lotes fuera del alcance. En la instancia real no hay membresías ni roles asociados al grupo y solo hay una finca, por lo que no se reprodujeron estos escenarios con cuentas reales. |
| Vencimiento y conservación | Chromium con autorización firmada y catálogos de prueba | Se adelantó el reloj. Reabrir sin conexión no renovó el permiso, al vencer se bloqueó la captura y conservaron ambos registros en IndexedDB. Después de restablecer la sesión de prueba la operación siguió pendiente. |
| Integración y límite de API | SuiteCRM real | `current-user` y los metadatos de Users mostraron el vínculo `SecurityGroups`. `meta/fields/SecurityGroups` y `module/SecurityGroups` devolvieron **400 Not Allowed** al cliente consultado. El cliente técnico siguió leyendo La Quiebra y recibió **403** en la ruta de permisos personales. |

Se repitieron **17 pruebas Java** y **una de Chromium**, todas aprobadas. El proveedor simulado se usa para comprobar el contrato OAuth, la autorización de Java y la conducta local. Los casos simulados no demuestran el comportamiento de credenciales, ACL o membresías de un usuario real.

## Diagnóstico de admin y sincronización de café

- La inspección se hizo en los contenedores activos `suitecrm810-app-1` y `suitecrm810-db-1`. La cuenta admin tiene estado activo tanto de usuario como de empleado. Se verificaron su membresía, el rol AgroSync Administrador de finca y la vinculación del grupo AgroSync La Quiebra con el registro La Quiebra.
- SuiteCRM registró emisiones de token OAuth delegado para esa cuenta. Eso prueba la aceptación en SuiteCRM, mientras que la resolución interna de permisos con la configuración real produjo `La Quiebra → ADMINISTRADOR_FINCA`. El resolutor se ejecutó en solo lectura sin emitir un token ni simular una autenticación exitosa. Ambas evidencias no demuestran por sí solas que el navegador haya establecido la sesión de Java.
- Se corrigieron dos fallos de diagnóstico. El callback antes convertía un error posterior a la identificación en el mismo mensaje de autenticación. Además, las fincas con perfiles incompatibles se descartaban sin avisar. No había una selección del último rol en Java. Ahora los perfiles se agrupan por usuario y finca, se informa el conflicto, y React muestra el nombre de la finca y los roles involucrados. Se ajustó la navegación con red para recuperar el HTML actual y usar el caché como respaldo offline.
- Las pruebas Java cubren el cambio de orden de perfiles, HTTP 409 para la finca conflictiva y los rechazos de escritura para Consulta, finca ajena y ausencia de CSRF. La prueba de Chromium conserva la cola tras una respuesta fallida y solo cambia a confirmada tras validar el acuse simulado.
- En una copia aislada de MariaDB, la ruta PHP real creó una cosecha usando las referencias y grupos de La Quiebra. El primer envío devolvió 201, el reintento idéntico 200, el contenido diferente con el mismo UUID 409 y la finca ajena 403. Se verificó que quedó un registro de prueba con el actor correcto en esa copia. La copia se eliminó después.
- La instancia activa seguía con cero cosechas al concluir la prueba aislada. Los dos pendientes del usuario quedaron sin enviar por el asistente. La confirmación del retorno de login en el navegador del titular y el primer envío intencional de esos pendientes deben verificarse en su sesión.

### Confirmación posterior en la instancia activa

El 19 de noviembre de 2026 quedó registrado un retorno OAuth delegado de admin y Java confirmó la sesión con una finca autorizada y cero conflictos. El único perfil resuelto fue Administrador de finca para La Quiebra. Después se verificaron en la instancia activa dos cosechas con dos IDs distintos, ambas creadas por admin, vinculadas por el lote a La Quiebra y asociadas al grupo AgroSync La Quiebra. La prueba aislada se había completado antes, cuando el CRM activo aún tenía cero cosechas. No se crearon usuarios ni se cambiaron contraseñas o asignaciones. Los casos de Consulta, finca ajena e inactividad con cuentas reales quedan pendientes según lo acordado.
Verificación final: 19 pruebas Java y una prueba Chromium aprobadas.

## Incremento Ganadería

- El build de Docker pasó **20 pruebas Java**. Se verificaron la consulta en finca autorizada, el rechazo a una finca ajena, el permiso de registro de eventos y la prohibición de crear animales para Registrador o de registrar eventos para Consulta. Las otras pruebas mantienen login delegado, conflictos por finca y sincronización de Café.
- PHP pasó lint en el resolutor, la nueva extensión y el registro de rutas.
- En una **copia aislada y descartable de MariaDB**, la extensión nativa recibió los datos rotulados `Madre de prueba aislada`, `Cría de prueba aislada` y `Revisión de prueba aislada`, con códigos `PRUEBA-…`. Rechazó primero la cría y el evento sin su referencia (409), creó los tres en orden (201) y devolvió 200 al repetir cada UUID. Un contenido cambiado y un código duplicado devolvieron 409. Una finca ajena devolvió 403. La consulta confirmó las referencias madre–cría y evento–animal.
- La huella del contenido completo de `ags_cosechas` en la instalación activa fue idéntica antes y después de la prueba aislada. La instancia contenía cuatro cosechas, incluidas las dos confirmadas del incremento anterior. La prueba no escribió animales ni eventos en la instancia activa.
- Pendiente de operación: primer registro ganadero real desde un navegador del usuario con su sesión de SuiteCRM. No hay registros ganaderos de prueba en la base activa.
- El frontend pasó tres pruebas Chromium, incluida una migración de IndexedDB v1 a v2 que conservó una recolección y su operación pendiente de prueba.

## Consulta semanal de Café

PHP pasó lint. Se verificó en solo lectura la respuesta del controlador nativo con la configuración real de La Quiebra en SuiteCRM. Devolvió cuatro cosechas existentes con IDs únicos dentro de sus semanas, 400 para una fecha inicial que no era lunes y 403 para una finca fuera de alcance. La prueba no creó ni modificó cosechas. Las pruebas Java ejercitaron también el rechazo anónimo y la consulta autorizada del perfil Consulta con el proveedor simulado.
