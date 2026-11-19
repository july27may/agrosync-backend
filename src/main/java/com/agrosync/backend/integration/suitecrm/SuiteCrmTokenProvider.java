package com.agrosync.backend.integration.suitecrm;

import com.agrosync.backend.config.SuiteCrmProperties;
import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
class SuiteCrmTokenProvider {
    private final RestClient client;
    private final SuiteCrmProperties properties;
    private final Clock clock;
    private Token cached;

    SuiteCrmTokenProvider(RestClient client, SuiteCrmProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    synchronized Token current() {
        if (cached == null || !clock.instant().isBefore(cached.refreshAt)) {
            cached = requestToken();
        }
        return cached;
    }

    synchronized Token afterRejection(Token rejected) {
        invalidate(rejected);
        return current();
    }

    synchronized void invalidate(Token rejected) {
        if (cached == rejected) {
            cached = null;
        }
    }

    private Token requestToken() {
        if (!properties.hasCredentials()) {
            throw ApiException.configuration();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        Instant started = clock.instant();
        JsonNode body;
        try {
            body = client.post().uri("/Api/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException failure) {
            throw ApiException.transport(failure);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 503) {
                throw ApiException.unavailable();
            }
            throw ApiException.authentication();
        } catch (RestClientException failure) {
            throw ApiException.invalidResponse();
        }

        if (body == null || !body.path("access_token").isTextual()
                || body.path("access_token").stringValue().isBlank()
                || !body.path("expires_in").isIntegralNumber()
                || !body.path("expires_in").canConvertToInt()
                || body.path("expires_in").intValue() <= 0) {
            throw ApiException.invalidResponse();
        }
        Duration ttl = Duration.ofSeconds(body.path("expires_in").intValue());
        Duration margin = Duration.ofMillis(Math.min(30_000, ttl.toMillis() / 10));
        return new Token(body.path("access_token").stringValue(), started.plus(ttl.minus(margin)));
    }

    static final class Token {
        private final String value;
        private final Instant refreshAt;

        private Token(String value, Instant refreshAt) {
            this.value = value;
            this.refreshAt = refreshAt;
        }

        String value() {
            return value;
        }
    }
}
