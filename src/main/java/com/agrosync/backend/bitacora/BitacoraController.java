package com.agrosync.backend.bitacora;

import com.agrosync.backend.auth.FarmAccess;
import com.agrosync.backend.auth.SuiteCrmIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bitacora")
class BitacoraController {
    private static final String ID = "[A-Za-z0-9_-]{1,36}";
    private final FarmAccess access;
    private final SuiteCrmIdentity crm;
    private final Bitacora audit;

    BitacoraController(FarmAccess access, SuiteCrmIdentity crm, Bitacora audit) {
        this.access = access;
        this.crm = crm;
        this.audit = audit;
    }

    @GetMapping
    JsonNode list(Principal principal, @RequestParam @Pattern(regexp = ID) String fincaId,
                  @RequestParam(required = false) @Pattern(regexp = "[0-9]{1,20}") String antes,
                  @RequestParam(required = false) UUID operationId) {
        access.requireFarm(principal, fincaId);
        return crm.get(principal, uri -> {
            uri.path(Bitacora.PATH).queryParam("fincaId", fincaId);
            if (antes != null) uri.queryParam("antes", antes);
            if (operationId != null) uri.queryParam("operationId", operationId.toString());
            return uri.build();
        });
    }

    record Resolution(@NotNull UUID intentoId, @NotNull UUID operationId, UUID relatedOperationId,
                      @NotNull @Pattern(regexp = ID) String fincaId,
                      @NotNull @Pattern(regexp = "inventario/movimientos|registros/(fincas|lotes|labores|insumos|cosechas|animales|eventos)") String modulo,
                      @NotNull @Pattern(regexp = "PROPONER_REVISION|CONSERVAR_CENTRAL|DESCARTAR_PENDIENTE") String causa) {}

    @PostMapping("/resoluciones")
    void resolve(Principal principal, @Valid @RequestBody Resolution input) {
        access.requireFarm(principal, input.fincaId());
        audit.append(principal, new Bitacora.Event(input.intentoId(), input.operationId(), input.relatedOperationId(),
                input.fincaId(), input.modulo(), "RESOLUCION", input.causa()));
    }
}
