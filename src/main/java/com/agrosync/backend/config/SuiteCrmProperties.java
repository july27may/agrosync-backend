package com.agrosync.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("suitecrm")
public record SuiteCrmProperties(
        URI baseUrl, String clientId, String clientSecret, Duration connectTimeout, Duration readTimeout
) {
    public SuiteCrmProperties {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("SUITECRM_BASE_URL debe ser una URL HTTP(S) sin credenciales ni parámetros");
        }
        requirePositive(connectTimeout, "SUITECRM_CONNECT_TIMEOUT");
        requirePositive(readTimeout, "SUITECRM_READ_TIMEOUT");
    }

    private static void requirePositive(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
    }

    public boolean hasCredentials() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    @Override
    public String toString() {
        return "SuiteCrmProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
