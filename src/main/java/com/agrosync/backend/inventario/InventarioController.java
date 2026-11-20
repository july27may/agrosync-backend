package com.agrosync.backend.inventario;

import com.agrosync.backend.auth.FarmAccess;
import com.agrosync.backend.auth.SuiteCrmIdentity;
import com.agrosync.backend.shared.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventario")
public class InventarioController {
    private static final String ID = "[A-Za-z0-9_-]{1,36}";
    private static final String CRM = "/Api/V8/custom/agrosync/inventario";
    private final FarmAccess access;
    private final SuiteCrmIdentity crm;

    public InventarioController(FarmAccess access, SuiteCrmIdentity crm) {
        this.access = access;
        this.crm = crm;
    }

    @GetMapping
    JsonNode list(Principal principal, @RequestParam @Pattern(regexp = ID) String fincaId) {
        access.requireFarm(principal, fincaId);
        return crm.get(principal, uri -> uri.path(CRM).queryParam("fincaId", fincaId).build());
    }

    record Movement(@NotNull UUID operationId, @NotNull @Pattern(regexp = ID) String fincaId,
                    @NotNull @Pattern(regexp = ID) String insumoId,
                    @NotNull @Pattern(regexp = "entrada|salida") String tipo,
                    @NotNull @Pattern(regexp = "kg|l|unidad") String unidad,
                    @NotNull @Pattern(regexp = "[0-9]{1,11}(\\.[0-9]{1,3})?") String cantidad,
                    @Pattern(regexp = ID) String origenId, @Size(max = 100) String referencia, Instant fecha) {}

    @PostMapping("/movimientos")
    JsonNode submit(Principal principal, @Valid @RequestBody Movement input) {
        var user = access.current(principal);
        user.requireFarm(input.fincaId());
        var role = user.permisos().get(input.fincaId());
        if (role == FarmAccess.Role.CONSULTA
                || (input.origenId() != null && role != FarmAccess.Role.ADMINISTRADOR_FINCA)) {
            throw ApiException.forbidden();
        }
        if (new java.math.BigDecimal(input.cantidad()).signum() <= 0) throw ApiException.invalidMovement();
        var result = crm.post(principal, CRM + "/movimientos", input, ApiException::conflictingInventory);
        var expected = input.operationId().toString();
        if (!expected.equals(result.path("operationId").stringValue(null))
                || !expected.equals(result.path("crmId").stringValue(null))) throw ApiException.invalidResponse();
        return result;
    }
}
