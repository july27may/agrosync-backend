package com.agrosync.backend.integration.suitecrm;

import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.function.Function;

@Component
public class SuiteCrmClient {
    private static final MediaType JSON_API = MediaType.parseMediaType("application/vnd.api+json");

    private final RestClient client;
    private final SuiteCrmTokenProvider tokens;

    SuiteCrmClient(RestClient client, SuiteCrmTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public JsonNode get(Function<UriBuilder, URI> uri) {
        var token = tokens.current();
        try {
            return request(uri, token);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() != 401) {
                throw translate(failure);
            }
        }
        var renewed = tokens.afterRejection(token);
        try {
            return request(uri, renewed);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 401) {
                tokens.invalidate(renewed);
            }
            throw translate(failure);
        }
    }

    private JsonNode request(Function<UriBuilder, URI> uri, SuiteCrmTokenProvider.Token token) {
        try {
            var body = client.get().uri(uri)
                    .headers(headers -> headers.setBearerAuth(token.value()))
                    .accept(JSON_API, MediaType.APPLICATION_JSON)
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.isObject()) {
                throw ApiException.invalidResponse();
            }
            return body;
        } catch (RestClientResponseException failure) {
            throw failure;
        } catch (ResourceAccessException failure) {
            throw ApiException.transport(failure);
        } catch (RestClientException failure) {
            throw ApiException.invalidResponse();
        }
    }

    private RuntimeException translate(RestClientResponseException failure) {
        return switch (failure.getStatusCode().value()) {
            case 401, 403 -> ApiException.authentication();
            case 503 -> ApiException.unavailable();
            // The calling module interprets record-specific failures.
            case 400, 404 -> failure;
            default -> ApiException.invalidResponse();
        };
    }
}
