# AgroSync Backend

Consulta la [matriz RF01–RF12 y los resultados del prototipo](https://github.com/july27may/agrosync-suitecrm/blob/main/docs/validacion-rf01-rf12.md).

Servicio de lectura de fincas para React. Usa Java 21, Spring Boot 4.1.2, Maven, Spring Web MVC, Validation, Actuator y RestClient. SuiteCRM 8.10.2 es la fuente de datos. El token OAuth2 identifica a la integración con SuiteCRM. Las personas inician sesión con Usuarios de SuiteCRM mediante un segundo cliente OAuth2.

## Estructura por funcionalidad

```text
com.agrosync.backend
├── AgrosyncApplication
├── config
├── fincas
│   ├── controller
│   ├── service
│   ├── dto
│   ├── mapper
│   └── integration
├── integration.suitecrm
└── shared.exception
```

Fincas reúne sus endpoints y casos de uso. Su adaptador `fincas.integration` conoce el nombre `ags_Fincas`, las rutas, los atributos y el error particular de registro inexistente. `FincaMapper` transforma JSON:API en los DTO que reciben los consumidores del backend.

Para los próximos módulos, crea un paquete por funcionalidad y añade solo las piezas que necesites. Reutiliza `SuiteCrmClient` para el transporte autenticado. La obtención y renovación del token, el reintento ante 401 y los timeouts quedan centralizados. Los errores comunes y su formato HTTP están en `shared.exception`. `AgrosyncApplication` sigue siendo el único punto de arranque.

## Arrancar con Maven

Desde este directorio, con Java 21 y Maven disponibles y SuiteCRM atendiendo en `127.0.0.1:8080`, ejecuta los comandos siguientes en Bash. Ingresa los dos valores cuando se soliciten. Bash exporta las variables al proceso de Spring. El backend no carga archivos `.env` automáticamente.

```bash
export SERVER_PORT=8081
export SUITECRM_BASE_URL=http://127.0.0.1:8080
export SUITECRM_CONNECT_TIMEOUT=3s SUITECRM_READ_TIMEOUT=5s
read -r -p 'Client ID de SuiteCRM: ' SUITECRM_CLIENT_ID
read -r -s -p 'Client secret de SuiteCRM: ' SUITECRM_CLIENT_SECRET
printf '\n'
export SUITECRM_CLIENT_ID SUITECRM_CLIENT_SECRET
mvn verify
mvn spring-boot:run
```

El artefacto empaquetado también se puede ejecutar con `java -jar target/agrosync-backend-0.1.0.jar` y el mismo entorno. Por defecto, en el host el servidor escucha en `127.0.0.1`.

## Arrancar con Docker

Con los contenedores existentes de SuiteCRM activos, exporta las credenciales con el procedimiento anterior y ejecuta `docker compose up -d --build` en este directorio. Si las credenciales se gestionan en un archivo privado fuera del proyecto, usa `docker compose --env-file /ruta/privada/agrosync.env up -d --build`. Compose lee el archivo para interpolar valores y su sección `environment` los inyecta al proceso Java.

El Compose del backend se une a la red externa `suitecrm810_default` y alcanza SuiteCRM por `http://app:80`. Publica el backend en `127.0.0.1:8081`. `SUITECRM_DOCKER_NETWORK` y `SUITECRM_DOCKER_BASE_URL` permiten ajustar el nombre de red y la URL dentro de Docker. Para reiniciar el contenedor ya creado, ejecuta `docker restart agrosync-backend-backend-1`. Para apagarlo sin requerir las variables de interpolación, ejecuta `docker stop agrosync-backend-backend-1`.

## Probar

Las rutas de catálogos requieren una sesión de usuario iniciada con SuiteCRM. Para comprobarlas usa el frontend o las herramientas de red de una sesión autorizada. Actuator permanece público y una lectura anónima de catálogos debe devolver 401.


```bash
curl -fsS 'http://127.0.0.1:8081/actuator/health'
curl -fsS 'http://127.0.0.1:8081/api/v1/fincas?page=1&size=20'
curl -fsS "http://127.0.0.1:8081/api/v1/fincas/<id-de-la-lista>"
curl -sS -i 'http://127.0.0.1:8081/api/v1/fincas/00000000-0000-4000-8000-000000000000'
```

La lista devuelve `items`, `page`, `size`, `recordsOnPage` y `totalPages`. La primera página es la 1. El tamaño admite valores de 1 a 100. `totalPages` es `null` cuando el CRM omite ese metadato, como sucede en páginas vacías. La salud indica que el proceso está activo. La verificación de conectividad con SuiteCRM se hace con las rutas de fincas.

Los errores incluyen `code` y `message`. Un ID inexistente devuelve 404. Esta instalación puede responder 400 para ese caso y el backend traduce únicamente el error que identifica el registro faltante. La falta de credenciales o la indisponibilidad de SuiteCRM devuelve 503. Un tiempo de espera agotado devuelve 504. Un rechazo de autenticación definitivo o una respuesta inesperada devuelve 502.

## Catálogos para captura de café

- `GET /api/v1/lotes?fincaId=<id>&page=1&size=20` usa `ags_lotes.finca_id` como filtro de igualdad en SuiteCRM. Cada elemento devuelve `id`, `nombre`, `codigo` y `fincaId`.
- `GET /api/v1/contactos?page=1&size=20` devuelve `id` y `nombre`. Compone el nombre con `first_name` y `last_name` de Contacts y usa `name` como respaldo.
- Ambas rutas conservan la estructura de paginación de Fincas y comparten su transporte y token de SuiteCRM. Los adaptadores y DTO están en `lotes` y `contactos`, y la lectura paginada de catálogos en `integration.suitecrm`.
- El origen CORS por defecto es `http://127.0.0.1:5173`. Para cambiarlo, exporta `AGROSYNC_FRONTEND_ORIGIN` o defínelo en la configuración privada que usa Compose y recrea el backend.

## SuiteCRM y mapeo

Se comprobó `POST /Api/access_token` con `Content-Type: application/x-www-form-urlencoded`, usando `client_credentials`, `client_id` y `client_secret`. SuiteCRM emitió un token con `expires_in=3600`. El backend conserva el token en memoria y lo renueva antes del vencimiento con un margen máximo de 30 segundos. Las solicitudes concurrentes comparten el token. Ante un 401 del CRM, renueva y repite la lectura una sola vez.

Se verificó `GET /Api/V8/meta/fields/ags_Fincas` y se confirmaron los nueve campos. Las consultas usan `GET /Api/V8/module/ags_Fincas` y `GET /Api/V8/module/ags_Fincas/{id}`, con un conjunto explícito de atributos. En JSON:API, el ID del registro aparece en `data.id`, separado de `data.attributes`.

| SuiteCRM | Backend | Tipo |
|---|---|---|
| `data.id` | `id` | Texto |
| `attributes.name` | `nombre` | Texto |
| `attributes.codigo_finca` | `codigo` | Texto |
| `attributes.estado` | `estado` | Texto con valor interno, por ejemplo `Activa` |
| `attributes.area_ha` | `areaHa` | Número JSON, `BigDecimal` en Java. SuiteCRM devolvió texto decimal |
| `attributes.ags_departamentos_id_c` | `departamentoId` | Texto |
| `attributes.ags_municipios_id_c` | `municipioId` | Texto |
| `attributes.ags_veredas_id_c` | `veredaId` | Texto |
| `attributes.contact_id_c` | `propietarioId` | Texto |

Los atributos opcionales ausentes, nulos o en blanco se devuelven como `null`.

## Validación

`mvn verify` ejecuta pruebas JUnit contra un servidor HTTP simulado y pruebas de los endpoints con MockMvc. Consulta [VALIDACION.md](VALIDACION.md) para ver los resultados y la evidencia de la consulta real.

## Identidad de SuiteCRM y permisos

El operador crea y administra las personas en **SuiteCRM → Administración → Usuarios**. Asocia a cada persona al grupo de la finca y del perfil que le corresponda. Cada grupo debe estar vinculado al registro Finca y a uno de los roles `AgroSync Administrador de finca`, `AgroSync Registrador` o `AgroSync Consulta`. Si una finca tiene perfiles diferentes, crea grupos separados. Los roles directos de un usuario no reemplazan esa relación. Una finca con dos perfiles AgroSync distintos para la misma persona aparece en `conflictos`. Java bloquea el acceso a esa finca con HTTP 409 y mantiene disponibles las demás. Corrige el grupo o el rol en SuiteCRM. Incluso un administrador global debe tener una asignación explícita para aparecer con permisos en AgroSync.

El botón **Ingresar con SuiteCRM** inicia Authorization Code con PKCE S256. Java guarda `state` y el verificador por cinco minutos en la sesión inicial, consume el intento una vez y cambia el ID de sesión después del login. SuiteCRM recibe y verifica la contraseña con su autenticador configurado. Java recibe el código de autorización, y el intercambio solo produce un token delegado si el proveedor lo acepta. Java comprueba además que exista un Usuario de SuiteCRM con estado `Active` y que su verificación nativa `User::isEnabled()` confirme que está habilitado. Java obtiene la identidad mediante `/Api/V8/current-user` y usa su `data.id` como clave estable. La sesión HTTP de Java usa cookie HttpOnly y SameSite Lax para recibir el retorno. En una instalación remota usa HTTPS y cookie Secure. Las rutas POST mantienen la protección CSRF. La sesión de Java expira por inactividad a los 30 minutos y al expirar o revocarse el token delegado se requiere un nuevo ingreso.

### Clientes OAuth2

| Cliente | Variables privadas del backend | Responsabilidad |
|---|---|---|
| Integración técnica existente | `SUITECRM_CLIENT_ID`, `SUITECRM_CLIENT_SECRET` | Consultas de integración del servidor. |
| AgroSync Inicio de sesión | `AGROSYNC_OAUTH_CLIENT_ID`, `AGROSYNC_OAUTH_CLIENT_SECRET` | Intercambio del código de autorización del usuario. |

El cliente de inicio de sesión ya está creado en SuiteCRM. Su retorno registrado es `http://127.0.0.1:8081/api/v1/auth/callback`. Usa `AGROSYNC_CRM_PUBLIC_URL` para la URL de SuiteCRM que abre el navegador y `AGROSYNC_OAUTH_CALLBACK` para el retorno registrado. Con Docker, la comunicación del servidor sigue usando `SUITECRM_BASE_URL` en la red de Docker. Con Maven, exporta las variables desde un archivo privado o desde el gestor de secretos. Los secretos no se incluyen en el frontend.

### Comprobación en cada solicitud

La API V8 nativa confirma la identidad mediante `/Api/V8/current-user`, que expone la relación `SecurityGroups`. En la instalación local, la consulta de metadatos y registros de `SecurityGroups` devolvió HTTP 400 con el mensaje de que el usuario de API no tiene acceso al módulo, incluso en la comprobación diagnóstica con el cliente de integración existente. Esto prueba la limitación para esa identidad y configuración, sin afirmar que el módulo sea inaccesible en todas las instalaciones. Se requiere resolver el cruce de membresías, roles y registros de finca dentro de SuiteCRM.

SuiteCRM expone una ruta personalizada de solo lectura en `GET /Api/V8/custom/agrosync/access`. El middleware OAuth de SuiteCRM valida el token. La ruta acepta exclusivamente tokens de clientes Authorization Code, identifica su propio usuario activo y obtiene los permisos por finca a partir de membresías, asociaciones de grupos, roles nativos y la ACL de vista de la finca. No recibe un ID de usuario del navegador. Java relee esta autorización en las operaciones protegidas y compara la identidad devuelta con la de la sesión. Una persona sin asignaciones ve una lista vacía de fincas y no puede descargar Contactos o autorizar el trabajo offline. Contactos se lee con el token delegado bajo los permisos nativos de SuiteCRM.

Java verifica permisos antes de usar el cliente técnico para leer Fincas o Lotes. El contrato de identidad `GET /api/v1/auth/me` devuelve `id`, `username`, `conflictos` y `permisos`, un mapa de ID de finca a perfil. En React, Administrador de finca y Registrador pueden capturar localmente en esas fincas. Consulta mantiene acceso de lectura en las suyas, incluso si la misma persona puede capturar en otra.

### Autorización offline y transición

El permiso firmado conserva los perfiles por finca y usa `AGROSYNC_OFFLINE_TTL`, configurado actualmente en `24h`. Es una política provisional pendiente de validación con la finca. Acepta duraciones como `90m` o `24h`. El servidor firma las fechas de emisión y vencimiento. Reabrir la aplicación, recargar o volver a conectarse conserva el vencimiento original. Para emitir un permiso nuevo se requiere una sesión válida, permisos vigentes y la acción **Preparar dispositivo offline**. Los permisos ya emitidos mantienen el vencimiento firmado. La sincronización se inicia manualmente con una sesión en línea. Java y SuiteCRM aplican el rol de escritura y validan la finca para cada operación. Las operaciones sin confirmación permanecen pendientes. Las capturas previas que no estén identificadas con el ID de usuario de SuiteCRM se conservan en IndexedDB bajo su identificador original. Su revisión y asignación requieren un proceso explícito.

## Sincronizar recolecciones de café

`POST /api/v1/cosechas/sync` exige sesión OAuth delegada, CSRF y el perfil **Administrador de finca** o **Registrador** para esa finca. Java comprueba el perfil vigente y envía la operación a la ruta personalizada de SuiteCRM con el token delegado. SuiteCRM confirma el usuario activo, resuelve el lote y su finca real, verifica que se pueda consultar al recolector y vuelve a comprobar el alcance y el perfil. Se registra el usuario delegado como creador. El alcance de grupos de la cosecha se ajusta a los grupos AgroSync de la finca.

El UUID `operationId` sirve como ID de la cosecha en SuiteCRM. Repetir el mismo contenido y actor devuelve el mismo acuse. Si el UUID ya existe con otro contenido, SuiteCRM devuelve conflicto y conserva el registro existente. El navegador muestra **Confirmado en SuiteCRM** solo si el acuse coincide con la operación. Los fallos conservan la cola para reintentar. Este flujo envía exclusivamente recolecciones de café.

## Ganadería

El paquete Java `ganaderia` usa la sesión delegada, la autorización vigente por finca y los errores compartidos. SuiteCRM guarda en `ags_animales` y `ags_eventos_sanitarios` y expone una extensión en `/Api/V8/custom/agrosync/ganaderia`. El cliente técnico de Café permanece separado.

| Ruta Java | Permiso |
|---|---|
| `GET /api/v1/ganaderia?fincaId=…` | Administrador de finca, Registrador o Consulta dentro de la finca asignada. |
| `POST /api/v1/ganaderia/animales` | Administrador de finca. |
| `POST /api/v1/ganaderia/eventos` | Administrador de finca o Registrador. |

Los POST requieren sesión activa y CSRF. El servidor valida la finca contra los grupos de SuiteCRM, comprueba que la madre sea una hembra de la misma finca y que el animal de un evento ya exista en esa finca. Si un responsable está presente, se verifica su acceso en Contactos. SuiteCRM usa el UUID de la operación como ID del registro central. Un reintento idéntico devuelve el mismo acuse. Un cambio de contenido para ese UUID, un código de animal duplicado en la finca o una referencia sin confirmar generan conflicto y conservan la operación local.

Archivos CRM: `custom/Extension/application/Ext/Api/V8/ganaderia.php`, resolutor compartido en `agrosync.php` y registro en `custom/application/Ext/Api/V8/Config/routes.php`.

## Consulta semanal de Café

`GET /api/v1/cosechas?fincaId=<id>&semana=<AAAA-MM-DD>` recibe el lunes de la semana. Java comprueba la sesión y la finca vigente. La extensión de SuiteCRM valida de nuevo el alcance con el token delegado y usa la finca real del lote para obtener las cosechas. Devuelve el peso en gramos y la tarifa histórica en centavos, junto con el ID central y el recolector. El contrato aplica a los perfiles de lectura, incluido Consulta.
