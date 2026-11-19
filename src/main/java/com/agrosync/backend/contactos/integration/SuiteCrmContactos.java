package com.agrosync.backend.contactos.integration;

import com.agrosync.backend.contactos.dto.Contacto;
import com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.function.Function;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog.requiredText;
import static com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog.text;

@Component
public class SuiteCrmContactos {
    private final SuiteCrmCatalog catalog;

    public SuiteCrmContactos(SuiteCrmCatalog catalog) {
        this.catalog = catalog;
    }

    public SuiteCrmCatalog.Page<Contacto> list(int page, int size, Function<Function<UriBuilder, URI>, JsonNode> get) {
        return catalog.page("Contacts", "first_name,last_name,name", Map.of(), page, size, record -> {
            var attrs = record.path("attributes");
            var name = Stream.of(text(attrs, "first_name"), text(attrs, "last_name"))
                    .filter(Objects::nonNull).map(String::strip).collect(Collectors.joining(" "));
            if (name.isBlank()) {
                name = Objects.requireNonNullElse(text(attrs, "name"), "Sin nombre");
            }
            return new Contacto(requiredText(record, "id"), name);
        }, get);
    }
}
