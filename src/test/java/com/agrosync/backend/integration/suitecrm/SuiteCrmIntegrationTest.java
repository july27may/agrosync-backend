package com.agrosync.backend.integration.suitecrm;

import com.agrosync.backend.config.SuiteCrmProperties;
import com.agrosync.backend.contactos.integration.SuiteCrmContactos;
import com.agrosync.backend.lotes.integration.SuiteCrmLotes;
import com.agrosync.backend.fincas.integration.SuiteCrmFincaClient;
import com.agrosync.backend.shared.exception.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SuiteCrmIntegrationTest {
    private static final String MODULE = "/Api/V8/module/ags_Fincas";
    private static final String TOKEN = "/Api/access_token";

    private final AtomicInteger tokens = new AtomicInteger();
    private final AtomicInteger records = new AtomicInteger();
    private final MutableClock clock = new MutableClock();
    private final ConcurrentLinkedDeque<Call> calls = new ConcurrentLinkedDeque<>();
    private HttpServer server;
    private SuiteCrmFincaClient client;
    private SuiteCrmCatalog catalog;
    private SuiteCrmClient suiteCrm;
    private int rejectedReads;
    private int ttl = 3600;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = client(Duration.ofSeconds(3));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void obtieneTokenUnaVezYLeeListaPaginadaYDetalle() {
        var page = client.list(2, 20);
        var detail = client.find("demo-id");

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.recordsOnPage()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.items()).containsExactly(detail);
        assertThat(detail.areaHa()).isEqualByComparingTo(new BigDecimal("10.00000000"));
        assertThat(detail.nombre()).isEqualTo("Finca de prueba");
        assertThat(detail.estado()).isEqualTo("Activa");
        assertThat(detail.departamentoId()).isEqualTo("departamento-demo");
        assertThat(detail.municipioId()).isNull();
        assertThat(detail.veredaId()).isNull();
        assertThat(detail.propietarioId()).isNull();
        assertThat(tokens).hasValue(1);

        var callsInOrder = new ArrayList<>(calls);
        var auth = callsInOrder.getFirst();
        assertThat(auth.method()).isEqualTo("POST");
        assertThat(auth.uri().getPath()).isEqualTo(TOKEN);
        assertThat(auth.contentType()).startsWith("application/x-www-form-urlencoded");
        assertThat(auth.body()).contains("grant_type=client_credentials", "client_id=test-id", "client_secret=test-secret");
        var list = callsInOrder.get(1);
        assertThat(list.uri().getPath()).isEqualTo(MODULE);
        assertThat(URLDecoder.decode(list.uri().getRawQuery(), StandardCharsets.UTF_8))
                .contains("page[number]=2", "page[size]=20", "sort=id", "fields[ags_Fincas]=name,codigo_finca");
        assertThat(callsInOrder.subList(1, 3)).extracting(Call::authorization)
                .containsExactly("Bearer token-1", "Bearer token-1");
    }

    @Test
    void renuevaPocoAntesDelVencimiento() {
        ttl = 10;
        client.list(1, 20);
        clock.advance(Duration.ofSeconds(8));
        client.list(1, 20);
        assertThat(tokens).hasValue(1);
        clock.advance(Duration.ofSeconds(1));
        client.find("demo-id");
        assertThat(tokens).hasValue(2);
        assertThat(calls.getLast().authorization()).isEqualTo("Bearer token-2");
    }

    @Test
    void renuevaYReintentaUnaVezAnte401() {
        rejectedReads = 1;
        assertThat(client.find("demo-id").id()).isEqualTo("demo-id");
        assertThat(tokens).hasValue(2);
        assertThat(records).hasValue(2);
        assertThat(calls.stream().filter(call -> "GET".equals(call.method())).map(Call::authorization))
                .containsExactly("Bearer token-1", "Bearer token-2");
    }

    @Test
    void terminaSiElSegundoIntentoTambienRecibe401() {
        rejectedReads = 2;
        var failure = assertThrows(ApiException.class, () -> client.find("demo-id"));
        assertThat(failure.code()).isEqualTo("CRM_AUTENTICACION");
        assertThat(tokens).hasValue(2);
        assertThat(records).hasValue(2);
    }

    @Test
    void unaSolaSolicitudOAuthParaLecturasConcurrentes() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var ready = new CountDownLatch(8);
            var launch = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    launch.await();
                    return client.find("demo-id").id();
                }));
            }
            boolean simultaneous = ready.await(5, TimeUnit.SECONDS);
            launch.countDown();
            assertThat(simultaneous).isTrue();
            for (var future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("demo-id");
            }
        }
        assertThat(tokens).hasValue(1);
        assertThat(records).hasValue(8);
    }

    @Test
    void convierteEl400DeFincaInexistenteEn404() {
        var failure = assertThrows(ApiException.class, () -> client.find("inexistente"));
        assertThat(failure.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(failure.code()).isEqualTo("FINCA_NO_ENCONTRADA");
    }

    @Test
    void conservaCamposOpcionalesVaciosYPaginaVacia() {
        var empty = client.find("vacia");
        assertThat(empty.codigo()).isNull();
        assertThat(empty.areaHa()).isNull();
        assertThat(empty.departamentoId()).isNull();
        var page = client.list(99, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.recordsOnPage()).isZero();
        assertThat(page.totalPages()).isNull();
    }

    @Test
    void conservaLaPrecisionDeUnAreaJsonNumerica() {
        assertThat(client.find("decimal").areaHa())
                .isEqualByComparingTo(new BigDecimal("1234567890.12345678"));
    }

    @Test
    void devuelve503SiCrmNoEstaDisponible() {
        stop();
        server = null;
        var failure = assertThrows(ApiException.class, () -> client.find("demo-id"));
        assertThat(failure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failure.code()).isEqualTo("CRM_NO_DISPONIBLE");
    }

    @Test
    void devuelve504SiCrmAgotaElTiempo() {
        client = client(Duration.ofMillis(500));
        var failure = assertThrows(ApiException.class, () -> client.find("lenta"));
        assertThat(failure.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(failure.code()).isEqualTo("CRM_TIEMPO_AGOTADO");
    }

    @Test
    void filtraLotesPorIdDeFincaYConservaLaPaginacion() {
        var page = new SuiteCrmLotes(catalog).list("finca-demo", 2, 10);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().fincaId()).isEqualTo("finca-demo");
        assertThat(page.items().getFirst().codigo()).isEqualTo("L-01");
        var request = calls.getLast();
        assertThat(request.uri().getPath()).isEqualTo("/Api/V8/module/ags_lotes");
        assertThat(request.uri().getQuery()).contains(
                "filter[finca_id][eq]=finca-demo", "filter[deleted][eq]=0", "page[number]=2", "page[size]=10");
        var failure = assertThrows(ApiException.class, () -> new SuiteCrmLotes(catalog).list("otra-finca", 1, 10));
        assertThat(failure.code()).isEqualTo("CRM_RESPUESTA_INVALIDA");
    }

    @Test
    void devuelveSoloIdentificadoresYNombresDeContactosReutilizandoElToken() {
        var page = new SuiteCrmContactos(catalog).list(1, 20, suiteCrm::get);
        assertThat(page.items()).extracting(contact -> contact.nombre())
                .containsExactly("Nombre Apellido", "Nombre alternativo");
        assertThat(page.items()).extracting(contact -> contact.id()).containsExactly("contact-1", "contact-2");
        assertThat(calls.getLast().uri().getQuery()).contains("fields[Contacts]=first_name,last_name,name");
        client.find("demo-id");
        assertThat(tokens).hasValue(1);
    }

    private SuiteCrmFincaClient client(Duration readTimeout) {
        var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var properties = new SuiteCrmProperties(uri, "test-id", "test-secret", Duration.ofSeconds(2), readTimeout);
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        var json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        var httpClient = RestClient.builder().baseUrl(uri.toString()).requestFactory(factory)
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json)))
                .build();
        var tokens = new SuiteCrmTokenProvider(httpClient, properties, clock);
        suiteCrm = new SuiteCrmClient(httpClient, tokens);
        catalog = new SuiteCrmCatalog(suiteCrm);
        return new SuiteCrmFincaClient(suiteCrm, json);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            var path = exchange.getRequestURI().getPath();
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Authorization")));

            if (TOKEN.equals(path)) {
                var number = tokens.incrementAndGet();
                send(exchange, 200, "application/json",
                        "{\"access_token\":\"token-" + number + "\",\"expires_in\":" + ttl + "}");
                return;
            }

            int request = records.incrementAndGet();
            if (request <= rejectedReads) {
                send(exchange, 401, "application/json", "{\"detail\":\"mock auth failure\"}");
                return;
            }
            var document = switch (path) {
                case "/Api/V8/module/ags_lotes" -> """
                        {"meta":{"total-pages":3},"data":[{"id":"lote-1","attributes":{
                        "name":"Lote de prueba","codigo_lote":"L-01","finca_id":"finca-demo"}}]}
                        """;
                case "/Api/V8/module/Contacts" -> """
                        {"meta":{"total-pages":1},"data":[
                        {"id":"contact-1","attributes":{"first_name":"Nombre","last_name":"Apellido","name":"Alternativo"}},
                        {"id":"contact-2","attributes":{"name":"Nombre alternativo"}}]}
                        """;
                case MODULE -> exchange.getRequestURI().getQuery().contains("=99")
                        ? "{\"data\":[]}"
                        : "{\"meta\":{\"total-pages\":3},\"data\":[" + record("10.00000000") + "]}";
                case MODULE + "/demo-id" -> "{\"data\":" + record("10.00000000") + "}";
                case MODULE + "/vacia" -> """
                        {"data":{"id":"vacia","attributes":{"name":"Finca vacía","area_ha":"","codigo_finca":null}}}
                        """;
                case MODULE + "/decimal" -> "{\"data\":" + record("10").replace("\"10\"", "1234567890.12345678") + "}";
                case MODULE + "/lenta" -> {
                    try {
                        Thread.sleep(1_500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    yield "{\"data\":" + record("10") + "}";
                }
                default -> null;
            };
            if (document == null) {
                send(exchange, 400, "application/json",
                        """
                        {"errors":{"detail":"ags_Fincas module with id inexistente is not found"}}
                        """);
                return;
            }
            send(exchange, 200, "application/vnd.api+json", document);
        }
    }

    private static String record(String area) {
        return """
                {"type":"ags_Fincas","id":"demo-id","attributes":{"name":"Finca de prueba",
                "codigo_finca":"PRUEBA","estado":"Activa","area_ha":"%s",
                "ags_departamentos_id_c":"departamento-demo","ags_municipios_id_c":"","ags_veredas_id_c":null}}
                """.formatted(area);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }

    private record Call(String method, URI uri, String contentType, String body, String authorization) {}

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.EPOCH);

        void advance(Duration amount) {
            current.updateAndGet(instant -> instant.plus(amount));
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException();
            }
            return this;
        }
    }
}
