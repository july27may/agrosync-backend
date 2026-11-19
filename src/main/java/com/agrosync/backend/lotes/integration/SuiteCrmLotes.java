package com.agrosync.backend.lotes.integration;

import com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog;
import com.agrosync.backend.lotes.dto.Lote;
import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog.requiredText;
import static com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog.text;

@Component
public class SuiteCrmLotes {
    private final SuiteCrmCatalog catalog;

    public SuiteCrmLotes(SuiteCrmCatalog catalog) {
        this.catalog = catalog;
    }

    public SuiteCrmCatalog.Page<Lote> list(String fincaId, int page, int size) {
        return catalog.page("ags_lotes", "name,codigo_lote,finca_id", Map.of("finca_id", fincaId),
                page, size, record -> {
                    var attrs = record.path("attributes");
                    var parent = requiredText(attrs, "finca_id");
                    if (!fincaId.equalsIgnoreCase(parent)) {
                        throw ApiException.invalidResponse();
                    }
                    return new Lote(requiredText(record, "id"), requiredText(attrs, "name"),
                            text(attrs, "codigo_lote"), parent);
                });
    }
}
