package com.agrosync.backend.integration.suitecrm;

import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import org.springframework.web.util.UriBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class SuiteCrmCatalog {
    private final SuiteCrmClient client;

    public SuiteCrmCatalog(SuiteCrmClient client) {
        this.client = client;
    }

    public <T> Page<T> page(String module, String fields, Map<String, String> filters,
                            int page, int size, Function<JsonNode, T> map) {
        return page(module, fields, filters, page, size, map, client::get);
    }

    public <T> Page<T> page(String module, String fields, Map<String, String> filters,
                            int page, int size, Function<JsonNode, T> map,
                            Function<Function<UriBuilder, URI>, JsonNode> get) {
        JsonNode response;
        try {
            response = get.apply(uri -> {
                uri.path("/Api/V8/module").pathSegment(module)
                        .queryParam("page[number]", page).queryParam("page[size]", size)
                        .queryParam("fields[" + module + "]", fields).queryParam("sort", "id")
                        .queryParam("filter[deleted][eq]", "0");
                filters.forEach((field, value) -> uri.queryParam("filter[" + field + "][eq]", value));
                return uri.build();
            });
        } catch (RestClientResponseException failure) {
            throw ApiException.invalidResponse();
        }
        var records = response.path("data");
        if (!records.isArray()) {
            throw ApiException.invalidResponse();
        }
        List<T> items = new ArrayList<>();
        records.forEach(record -> items.add(map.apply(record)));
        var total = response.path("meta").path("total-pages");
        return new Page<>(List.copyOf(items), page, size, items.size(),
                total.isNumber() && total.canConvertToInt() ? total.intValue() : null);
    }

    public static String text(JsonNode object, String field) {
        var value = object.path(field).stringValue(null);
        return value == null || value.isBlank() ? null : value;
    }

    public static String requiredText(JsonNode object, String field) {
        var value = text(object, field);
        if (value == null) {
            throw ApiException.invalidResponse();
        }
        return value;
    }

    public record Page<T>(List<T> items, int page, int size, int recordsOnPage, Integer totalPages) {}
}
