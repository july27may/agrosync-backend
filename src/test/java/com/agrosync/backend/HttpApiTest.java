package com.agrosync.backend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"suitecrm.client-id=", "suitecrm.client-secret=",
        "agrosync.offline-ttl=90m", "AGROSYNC_OAUTH_CLIENT_ID=cliente-delegado-prueba", "AGROSYNC_OAUTH_CLIENT_SECRET=secreto-simulado"})
class HttpApiTest {
    private static final String FARM = "demo-id";
    private static final String SESSION = "/api/v1/auth";
    private static final AtomicInteger EXCHANGES = new AtomicInteger();
    private static final AtomicReference<String> FORM = new AtomicReference<>();
    private static final AtomicReference<String> PERMISSIONS = new AtomicReference<>();
    private static final AtomicReference<String> EXPECTED_DEVICE_VERIFIER = new AtomicReference<>();
    private static final HttpServer CRM = mockCrm();

    @DynamicPropertySource
    static void crm(DynamicPropertyRegistry properties) {
        properties.add("suitecrm.base-url", () -> "http://127.0.0.1:" + CRM.getAddress().getPort());
    }

    @AfterAll
    static void stop() { CRM.stop(0); }

    @Autowired private WebApplicationContext context;
    @Autowired private tools.jackson.databind.json.JsonMapper json;
    private MockMvc http;

    @BeforeEach
    void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        PERMISSIONS.set("""
                {"id":"usuario-id","fincas":[{"id":"demo-id","role":"REGISTRADOR"},{"id":"segunda","role":"CONSULTA"}]}
                """);
    }

    @Test
    void mantieneSaludPublicaYProtegeCatalogos() throws Exception {
        http.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        http.perform(get("/api/v1/fincas")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/lotes").param("fincaId", FARM)).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/contactos")).andExpect(status().isUnauthorized());
        http.perform(post(SESSION + "/login").with(csrf()).param("username", "persona"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void validaStateYConsumeLaSolicitudUnaSolaVez() throws Exception {
        var flow = begin();
        int previous = EXCHANGES.get();
        http.perform(get(SESSION + "/callback").session(flow.session()).param("state", "otro").param("code", "codigo"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("http://127.0.0.1:5173/?login=error"));
        http.perform(get(SESSION + "/callback").session(flow.session()).param("state", flow.state()).param("code", "codigo"))
                .andExpect(redirectedUrl("http://127.0.0.1:5173/?login=error"));
        assertThat(EXCHANGES.get()).isEqualTo(previous);
    }

    @Test
    void identificaAlUsuarioDeSuiteCrmYLeeRolesVigentesPorFinca() throws Exception {
        var session = login();
        http.perform(get(SESSION + "/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("usuario-id")).andExpect(jsonPath("$.username").value("persona"))
                .andExpect(jsonPath("$.permisos.demo-id").value("REGISTRADOR"))
                .andExpect(jsonPath("$.permisos.segunda").value("CONSULTA"));
        assertThat(FORM.get()).contains("grant_type=authorization_code", "client_id=cliente-delegado-prueba",
                "code=codigo", "code_verifier=");
        assertThat(EXPECTED_DEVICE_VERIFIER.get()).isEqualTo(verifierChallenge(FORM.get()));
        http.perform(get("/api/v1/fincas/otra").session(session)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/lotes").param("fincaId", "otra").session(session)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/contactos").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].nombre").value("Persona de prueba"));
        PERMISSIONS.set("{\"id\":\"usuario-id\",\"fincas\":[]}");
        http.perform(get("/api/v1/fincas").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        http.perform(get("/api/v1/contactos").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void firmaPermisosOfflineConCsrfYConservaValidaciones() throws Exception {
        var session = login();
        http.perform(post(SESSION + "/offline").session(session)).andExpect(status().isForbidden());
        var signed = http.perform(post(SESSION + "/offline").session(session).with(csrf())
                        .param("deviceId", "00000000-0000-4000-8000-000000000001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signature").isString())
                .andReturn().getResponse().getContentAsByteArray();
        var payload = java.util.Base64.getUrlDecoder().decode(json.readTree(signed).path("payload").stringValue());
        var grant = json.readTree(payload);
        assertThat(grant.path("expiresAt").longValue() - grant.path("issuedAt").longValue())
                .isEqualTo(java.time.Duration.ofMinutes(90).toMillis());
        http.perform(get("/api/v1/fincas").session(session).param("page", "0")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/lotes").session(session)).andExpect(status().isBadRequest());
        http.perform(post(SESSION + "/logout").session(session).with(csrf()).header("Origin", "http://127.0.0.1:5173"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-AgroSync-Logout-Url", "http://127.0.0.1:8080/logout"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-AgroSync-Logout-Url"));
        http.perform(get(SESSION + "/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void aceptaSoloElOrigenDeReact() throws Exception {
        http.perform(options("/api/v1/contactos").header("Origin", "http://127.0.0.1:5173")
                        .header("Access-Control-Request-Method", "GET")).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
        http.perform(options("/api/v1/contactos").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
    }

    @Test
    void informaConflictosPorFincaSinElegirElUltimoRol() throws Exception {
        var session = login();
        for (String roles : new String[]{"\"CONSULTA\",\"REGISTRADOR\"", "\"REGISTRADOR\",\"CONSULTA\""}) {
            PERMISSIONS.set("""
                    {"id":"usuario-id","fincas":[{"id":"demo-id","role":"REGISTRADOR"}],
                     "conflictos":[{"id":"segunda","nombre":"Otra finca","roles":[%s]}]}
                    """.formatted(roles));
            http.perform(get(SESSION + "/me").session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.permisos.demo-id").value("REGISTRADOR"))
                    .andExpect(jsonPath("$.permisos.segunda").doesNotExist())
                    .andExpect(jsonPath("$.conflictos[0].nombre").value("Otra finca"));
            http.perform(get("/api/v1/lotes").session(session).param("fincaId", "segunda"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PERFILES_FINCA_INCOMPATIBLES"));
        }
    }

    @Test
    void permiteSincronizarSoloAUsuarioAutorizadoPorFinca() throws Exception {
        var session = login();
        String payload = """
                {"operationId":"00000000-0000-4000-8000-000000000002",
                "fincaId":"demo-id","loteId":"lote","recolectorId":"contacto","fecha":"2026-11-19",
                "pesoGramos":1234,"tarifaCentavos":150000,"estadoCafe":"cereza"}
                """;
        http.perform(post("/api/v1/cosechas/sync").session(session).contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/cosechas/sync").session(session).with(csrf()).contentType("application/json")
                        .content(payload.replace("demo-id", "segunda")))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/cosechas/sync").session(session).with(csrf()).contentType("application/json")
                        .content(payload.replace("demo-id", "ajena")))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/cosechas/sync").session(session).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.crmId").value("00000000-0000-4000-8000-000000000002"));
    }

    @Test
    void limitaGanaderiaPorFincaYAccion() throws Exception {
        var session = login();
        var animal = """
                {"operationId":"00000000-0000-4000-8000-000000000002","fincaId":"demo-id",
                 "codigo":"PRUEBA","nombre":"Animal de prueba","sexo":"hembra","estado":"activo"}
                """;
        var event = """
                {"operationId":"00000000-0000-4000-8000-000000000002","fincaId":"demo-id",
                 "animalId":"referencia","nombre":"Evento de prueba","fecha":"2026-11-19","tipo":"revision"}
                """;
        http.perform(get("/api/v1/ganaderia").session(session).param("fincaId", "segunda"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.animales").isArray());
        http.perform(get("/api/v1/ganaderia").session(session).param("fincaId", "ajena"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/ganaderia/animales").session(session).with(csrf())
                        .contentType("application/json").content(animal)).andExpect(status().isForbidden());
        http.perform(post("/api/v1/ganaderia/eventos").session(session).with(csrf())
                        .contentType("application/json").content(event.replace("demo-id","segunda")))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/ganaderia/eventos").session(session).with(csrf())
                        .contentType("application/json").content(event)).andExpect(status().isOk())
                .andExpect(jsonPath("$.crmId").value("00000000-0000-4000-8000-000000000002"));
        PERMISSIONS.set("{\"id\":\"usuario-id\",\"fincas\":[{\"id\":\"demo-id\",\"role\":\"ADMINISTRADOR_FINCA\"}]}");
        http.perform(post("/api/v1/ganaderia/animales").session(session).with(csrf())
                        .contentType("application/json").content(animal)).andExpect(status().isOk());
    }

    @Test
    void consultaCosechasSoloDeFincaAutorizadaYConSemanaValida() throws Exception {
        var session = login();
        http.perform(get("/api/v1/cosechas").param("fincaId", "demo-id").param("semana", "2026-11-16"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/cosechas").session(session).param("fincaId", "ajena")
                        .param("semana", "2026-11-16"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/cosechas").session(session).param("fincaId", "segunda")
                        .param("semana", "2026-11-19"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/v1/cosechas").session(session).param("fincaId", "segunda")
                        .param("semana", "2026-11-16"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    }

    private record Started(MockHttpSession session, String state) {}

    private Started begin() throws Exception {
        var result = http.perform(get(SESSION + "/login")).andExpect(status().isSeeOther()).andReturn();
        var query = UriComponentsBuilder.fromUriString(result.getResponse().getHeader("Location")).build().getQueryParams();
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        EXPECTED_DEVICE_VERIFIER.set(query.getFirst("code_challenge"));
        return new Started((MockHttpSession) result.getRequest().getSession(false), query.getFirst("state"));
    }

    private MockHttpSession login() throws Exception {
        var flow = begin();
        var response = http.perform(get(SESSION + "/callback").session(flow.session())
                        .param("state", flow.state()).param("code", "codigo"))
                .andExpect(redirectedUrl("http://127.0.0.1:5173/")).andReturn();
        return (MockHttpSession) response.getRequest().getSession(false);
    }

    private static String verifierChallenge(String form) throws Exception {
        var entry = java.util.Arrays.stream(form.split("&")).filter(part -> part.startsWith("code_verifier="))
                .findFirst().orElseThrow().substring("code_verifier=".length());
        var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(URLDecoder.decode(entry, StandardCharsets.UTF_8).getBytes(StandardCharsets.US_ASCII));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static HttpServer mockCrm() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    int status = 200;
                    String json;
                    if (path.equals("/Api/access_token")) {
                        FORM.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        EXCHANGES.incrementAndGet();
                        json = "{\"access_token\":\"delegado\"}";
                    } else if (!"Bearer delegado".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        status = 401;
                        json = "{}";
                    } else {
                        String route = path.equals("/Api/V8/custom/agrosync/cosechas")
                                ? exchange.getRequestMethod() + " " + path : path;
                        json = switch (route) {
                            case "/Api/V8/current-user" -> "{\"data\":{\"id\":\"usuario-id\",\"attributes\":{\"user_name\":\"persona\"}}}";
                            case "/Api/V8/custom/agrosync/access" -> PERMISSIONS.get();
                            case "GET /Api/V8/custom/agrosync/cosechas" ->
                                "fincaId=segunda&semana=2026-11-16".equals(exchange.getRequestURI().getQuery())
                                    ? "{\"items\":[]}" : "{}";
                            case "/Api/V8/custom/agrosync/ganaderia" -> "{\"animales\":[],\"eventos\":[]}";
                            case "/Api/V8/custom/agrosync/ganaderia/animales", "/Api/V8/custom/agrosync/ganaderia/eventos",
                                 "POST /Api/V8/custom/agrosync/cosechas" -> "{\"operationId\":\"00000000-0000-4000-8000-000000000002\",\"crmId\":\"00000000-0000-4000-8000-000000000002\"}";
                            case "/Api/V8/module/Contacts" -> "{\"data\":[{\"id\":\"contacto\",\"attributes\":{\"first_name\":\"Persona de prueba\"}}]}";
                            default -> "{}";
                        };
                    }
                    var bytes = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
