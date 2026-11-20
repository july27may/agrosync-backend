package com.agrosync.backend.bitacora;

import com.agrosync.backend.auth.SuiteCrmIdentity;
import com.agrosync.backend.shared.exception.ApiException;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class Bitacora {
    static final String PATH = "/Api/V8/custom/agrosync/bitacora";
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Bitacora.class);
    private static final Set<String> CAUSES = Set.of("CRM_NO_DISPONIBLE", "CRM_TIEMPO_AGOTADO",
            "CRM_RESPUESTA_INVALIDA", "AUTENTICACION_REQUERIDA", "ACCESO_DENEGADO", "COSECHA_CONFLICTIVA",
            "GANADERIA_CONFLICTO", "INVENTARIO_CONFLICTO", "REGISTRO_CONFLICTIVO", "VERSION_INCOMPATIBLE",
            "CODIGO_DUPLICADO", "OPERACION_REUTILIZADA", "REFERENCIA_CICLICA", "UNIDAD_INMUTABLE",
            "CONFIGURACION_ROL", "SALDO_INSUFICIENTE", "INSUMO_CAMBIADO", "COMPENSACION_INVALIDA");
    private final SuiteCrmIdentity crm;

    public Bitacora(SuiteCrmIdentity crm) {
        this.crm = crm;
    }

    record Event(UUID intentoId, UUID operationId, UUID relatedOperationId, String fincaId, String modulo,
                 String evento, String causa) {
        Event result(String event, String cause) {
            return new Event(intentoId, operationId, null, fincaId, modulo, event, cause);
        }
    }

    public <T> T sync(Principal principal, UUID operation, String farm, String module, Supplier<T> business) {
        var start = new Event(UUID.randomUUID(), operation, null, farm, module, "INTENTO", null);
        append(principal, start);
        try {
            var receipt = business.get();
            finish(principal, start.result("CONFIRMADO", null));
            return receipt;
        } catch (ApiException failure) {
            var cause = CAUSES.contains(failure.code()) ? failure.code() : "ERROR_ENVIO";
            finish(principal, start.result(failure.status().value() == 409 ? "CONFLICTO" : "ERROR", cause));
            throw failure;
        } catch (RuntimeException failure) {
            finish(principal, start.result("ERROR", "ERROR_ENVIO"));
            throw failure;
        }
    }

    private void finish(Principal principal, Event event) {
        try {
            append(principal, event);
        } catch (ApiException unavailable) {
            // Preserve the business response and its idempotency. The central attempt remains visible.
            LOG.warn("Bitácora sin resultado: operación={} intento={} estado={}",
                    event.operationId(), event.intentoId(), event.evento());
        }
    }

    void append(Principal principal, Event event) {
        var receipt = crm.post(principal, PATH, event, ApiException::auditConflict);
        if (!event.intentoId().toString().equals(receipt.path("intentoId").stringValue(null))
                || !event.evento().equals(receipt.path("evento").stringValue(null))) throw ApiException.invalidResponse();
    }
}
