package com.agrosync.backend.auth;

import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.function.Function;

@Component
public class SuiteCrmIdentity {
    private final RestClient client;
    private final String clientId;
    private final String secret;
    private final URI crmPublic;
    private final URI callback;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    SuiteCrmIdentity(RestClient client, Clock clock,
                     @Value("${AGROSYNC_OAUTH_CLIENT_ID:}") String clientId,
                     @Value("${AGROSYNC_OAUTH_CLIENT_SECRET:}") String secret,
                     @Value("${AGROSYNC_CRM_PUBLIC_URL:http://127.0.0.1:8080}") URI crmPublic,
                     @Value("${AGROSYNC_OAUTH_CALLBACK:http://127.0.0.1:8081/api/v1/auth/callback}") URI callback) {
        this.client = client;
        this.clock = clock;
        this.clientId = clientId;
        this.secret = secret;
        this.crmPublic = crmPublic;
        this.callback = callback;
    }

    record Flow(String state, String verifier, long createdAt) {}
    record Identity(String id, String username, String token) implements Principal {
        @Override public String getName() { return id; }
        @Override public String toString() { return "SuiteCrmIdentity[" + id + "]"; }
    }

    Flow newFlow() {
        if (clientId.isBlank() || secret.isBlank()) throw ApiException.configuration();
        return new Flow(randomValue(), randomValue(), clock.millis());
    }

    URI authorizationUrl(Flow flow) {
        return UriComponentsBuilder.fromUri(crmPublic).path("/Api/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", "{callback}").queryParam("state", flow.state())
                .queryParam("code_challenge_method", "S256")
                .queryParam("code_challenge", challenge(flow.verifier()))
                .encode().buildAndExpand(callback.toString()).toUri();
    }

    Identity finish(Flow flow, String state, String code) {
        if (flow == null || state == null || code == null || code.isBlank() || code.length() > 8192
                || clock.millis() - flow.createdAt() > 300_000
                || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), flow.state().getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthenticated();
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        form.add("redirect_uri", callback.toString());
        form.add("code_verifier", flow.verifier());
        form.add("code", code);
        try {
            var document = client.post().uri("/Api/access_token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            var token = document == null ? null : document.path("access_token").stringValue(null);
            if (token == null || token.isBlank()) throw ApiException.invalidResponse();
            var record = delegatedGet(token, uri -> uri.path("/Api/V8/current-user").build()).path("data");
            var id = record.path("id").stringValue(null);
            var name = record.path("attributes").path("user_name").stringValue(null);
            if (id == null || id.isBlank() || name == null || name.isBlank()) throw ApiException.invalidResponse();
            return new Identity(id, name, token);
        } catch (ResourceAccessException problem) {
            throw ApiException.transport(problem);
        } catch (RestClientResponseException problem) {
            throw ApiException.unauthenticated();
        } catch (RestClientException problem) {
            throw ApiException.invalidResponse();
        }
    }

    public JsonNode get(Principal principal, Function<UriBuilder, URI> uri) {
        return delegatedGet(identity(principal).token(), uri);
    }

    public JsonNode createHarvest(Principal principal, Object body) {
        return post(principal, "/Api/V8/custom/agrosync/cosechas", body, ApiException::conflictingHarvest);
    }

    public JsonNode post(Principal principal, String path, Object body, java.util.function.Supplier<ApiException> conflict) {
        var user = identity(principal);
        try {
            var document = client.post().uri(path)
                    .headers(headers -> headers.setBearerAuth(user.token())).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            if (document == null || !document.isObject()) throw ApiException.invalidResponse();
            return document;
        } catch (ResourceAccessException problem) {
            throw ApiException.transport(problem);
        } catch (RestClientResponseException problem) {
            throw switch (problem.getStatusCode().value()) {
                case 401 -> ApiException.unauthenticated();
                case 403 -> ApiException.forbidden();
                case 409 -> conflict.get();
                case 503 -> ApiException.unavailable();
                default -> ApiException.invalidResponse();
            };
        } catch (RestClientException problem) {
            throw ApiException.invalidResponse();
        }
    }

    private JsonNode delegatedGet(String token, Function<UriBuilder, URI> uri) {
        try {
            var document = client.get().uri(uri).headers(headers -> headers.setBearerAuth(token))
                    .accept(MediaType.parseMediaType("application/vnd.api+json"), MediaType.APPLICATION_JSON)
                    .retrieve().body(JsonNode.class);
            if (document == null || !document.isObject()) throw ApiException.invalidResponse();
            return document;
        } catch (ResourceAccessException problem) {
            throw ApiException.transport(problem);
        } catch (RestClientResponseException problem) {
            throw switch (problem.getStatusCode().value()) {
                case 401 -> ApiException.unauthenticated();
                case 403 -> ApiException.forbidden();
                default -> ApiException.invalidResponse();
            };
        } catch (RestClientException problem) {
            throw ApiException.invalidResponse();
        }
    }

    Identity identity(Principal principal) {
        if (principal instanceof Authentication auth && auth.getPrincipal() instanceof Identity user) return user;
        throw ApiException.unauthenticated();
    }

    private String randomValue() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
