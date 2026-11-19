package com.agrosync.backend.fincas.integration;

import com.agrosync.backend.fincas.dto.Finca;
import com.agrosync.backend.fincas.dto.FincaPage;
import com.agrosync.backend.fincas.mapper.FincaMapper;
import com.agrosync.backend.integration.suitecrm.SuiteCrmClient;
import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.function.Function;

@Component
public class SuiteCrmFincaClient {
    private static final String RECORDS = "/Api/V8/module/ags_Fincas";
    private static final String FIELDS = String.join(",", "name", "codigo_finca", "estado", "area_ha",
            "ags_departamentos_id_c", "ags_municipios_id_c", "ags_veredas_id_c", "contact_id_c");

    private final SuiteCrmClient client;
    private final JsonMapper json;

    public SuiteCrmFincaClient(SuiteCrmClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public FincaPage list(int page, int size) {
        var response = read(uri -> uri.path(RECORDS)
                .queryParam("page[number]", page)
                .queryParam("page[size]", size)
                .queryParam("sort", "id")
                .queryParam("fields[ags_Fincas]", FIELDS)
                .build(), null);
        return FincaMapper.page(response, page, size);
    }

    public Finca find(String id) {
        var response = read(uri -> uri.path(RECORDS).pathSegment(id)
                .queryParam("fields[ags_Fincas]", FIELDS).build(), id);
        return FincaMapper.record(response.path("data"));
    }

    private JsonNode read(Function<UriBuilder, URI> uri, String id) {
        try {
            return client.get(uri);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (id != null && (status == 404 || (status == 400 && isMissingRecord(failure, id)))) {
                throw ApiException.notFound("FINCA_NO_ENCONTRADA", "No se encontró la finca.");
            }
            throw ApiException.invalidResponse();
        }
    }

    private boolean isMissingRecord(RestClientResponseException failure, String id) {
        try {
            var body = json.readTree(failure.getResponseBodyAsByteArray());
            return ("ags_Fincas module with id " + id + " is not found")
                    .equals(body.path("errors").path("detail").stringValue(null));
        } catch (JacksonException ignored) {
            return false;
        }
    }

}
