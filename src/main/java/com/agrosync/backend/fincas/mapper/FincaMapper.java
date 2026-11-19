package com.agrosync.backend.fincas.mapper;

import com.agrosync.backend.fincas.dto.Finca;
import com.agrosync.backend.fincas.dto.FincaPage;
import com.agrosync.backend.shared.exception.ApiException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;

public final class FincaMapper {
    private FincaMapper() {}

    public static FincaPage page(JsonNode response, int page, int size) {
        var data = response.path("data");
        if (!data.isArray()) {
            throw ApiException.invalidResponse();
        }
        var items = new ArrayList<Finca>();
        data.forEach(item -> items.add(record(item)));
        var pages = response.path("meta").path("total-pages");
        Integer totalPages = pages.isNumber() && pages.canConvertToInt() ? pages.intValue() : null;
        return new FincaPage(items, page, size, items.size(), totalPages);
    }

    public static Finca record(JsonNode record) {
        var id = text(record, "id");
        var attributes = record.path("attributes");
        if (id == null || !attributes.isObject()) {
            throw ApiException.invalidResponse();
        }
        return new Finca(id, text(attributes, "name"), text(attributes, "codigo_finca"),
                text(attributes, "estado"), decimal(attributes), text(attributes, "ags_departamentos_id_c"),
                text(attributes, "ags_municipios_id_c"), text(attributes, "ags_veredas_id_c"),
                text(attributes, "contact_id_c"));
    }

    private static String text(JsonNode parent, String name) {
        var value = parent.path(name);
        String text = value.stringValue(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static BigDecimal decimal(JsonNode attributes) {
        var value = attributes.path("area_ha");
        if (value.isNumber()) {
            return value.decimalValue();
        }
        String text = value.stringValue(null);
        if (value.isMissingNode() || value.isNull() || (text != null && text.isBlank())) {
            return null;
        }
        try {
            if (text == null) {
                throw ApiException.invalidResponse();
            }
            return new BigDecimal(text.strip());
        } catch (NumberFormatException ignored) {
            throw ApiException.invalidResponse();
        }
    }
}
