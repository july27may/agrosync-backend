package com.agrosync.backend.registros;

import com.agrosync.backend.auth.FarmAccess;
import com.agrosync.backend.auth.SuiteCrmIdentity;
import com.agrosync.backend.shared.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/registros/{modulo:fincas|lotes|labores|insumos|cosechas}")
public class RegistrosController {
    private static final String ID = "[A-Za-z0-9_-]{1,36}";
    private static final String CRM = "/Api/V8/custom/agrosync/registros/";
    private final FarmAccess access;
    private final SuiteCrmIdentity crm;

    public RegistrosController(FarmAccess access, SuiteCrmIdentity crm) {
        this.access = access;
        this.crm = crm;
    }

    @GetMapping
    JsonNode list(Principal principal, @PathVariable String modulo, @RequestParam @Pattern(regexp = ID) String fincaId) {
        access.requireFarm(principal, fincaId);
        return crm.get(principal, uri -> uri.path(CRM + modulo).queryParam("fincaId", fincaId).build());
    }

    @GetMapping("/alta")
    JsonNode farmCreation(Principal principal, @PathVariable String modulo) {
        if (!modulo.equals("fincas")) throw ApiException.forbidden();
        return crm.get(principal, uri -> uri.path(CRM + "fincas/alta").build());
    }

    record FarmCreate(@NotNull UUID operationId, @NotBlank @Size(max = 255) String nombre,
                      @NotBlank @Size(max = 30) String codigo) {}

    @PostMapping("/alta")
    JsonNode newFarm(Principal principal, @PathVariable String modulo, @Valid @RequestBody FarmCreate input) {
        if (!modulo.equals("fincas")) throw ApiException.forbidden();
        var result = crm.post(principal, CRM + "fincas/alta", input, ApiException::conflictingRecord);
        var id = input.operationId().toString();
        if (!id.equals(result.path("operationId").stringValue(null))
                || !id.equals(result.path("fincaId").stringValue(null))) throw ApiException.invalidResponse();
        return result;
    }

    record Change(@NotNull UUID operationId, @NotNull @Pattern(regexp = ID) String fincaId,
                  @Pattern(regexp = ID) String id, @Pattern(regexp = "[a-f0-9]{64}") String version,
                  @NotBlank @Size(max = 255) String nombre, @Size(max = 30) String codigo,
                  @Pattern(regexp = ID) String loteId, LocalDate fecha,
                  @Pattern(regexp = "siembra|fertilizacion|control_malezas|poda|otra") String tipo,
                  @Pattern(regexp = ID) String recolectorId,
                  @Pattern(regexp = "cereza|pergamino") String estadoCafe,
                  @Pattern(regexp = "[0-9]{1,11}(\\.[0-9]{1,3})?") String pesoKg,
                  @Pattern(regexp = "[0-9]{1,12}(\\.[0-9]{1,2})?") String tarifaKg,
                  @Pattern(regexp = "kg|l|unidad") String unidad,
                  @Pattern(regexp = "abono|alimento|medicamento|otro") String categoria) {}

    @PostMapping
    JsonNode apply(Principal principal, @PathVariable String modulo, @Valid @RequestBody Change input) {
        var user = access.current(principal);
        user.requireFarm(input.fincaId());
        var role = user.permisos().get(input.fincaId());
        if (role != FarmAccess.Role.ADMINISTRADOR_FINCA
                && !(role == FarmAccess.Role.REGISTRADOR && modulo.equals("labores") && input.id() == null)) {
            throw ApiException.forbidden();
        }
        if ((input.id() != null && input.version() == null)
                || (modulo.equals("fincas") && !input.fincaId().equals(input.id()))
                || (modulo.equals("labores") && (input.loteId() == null || input.fecha() == null || input.tipo() == null))
                || (modulo.equals("cosechas") && (input.id() == null || input.loteId() == null || input.fecha() == null
                        || input.recolectorId() == null || input.estadoCafe() == null
                        || !positive(input.pesoKg()) || !positive(input.tarifaKg())))
                || (java.util.Set.of("fincas", "lotes", "insumos").contains(modulo)
                        && (input.codigo() == null || input.codigo().isBlank()))
                || (modulo.equals("insumos") && (input.unidad() == null || input.categoria() == null))) {
            throw ApiException.invalidRecord();
        }
        var result = crm.post(principal, CRM + modulo, input, ApiException::conflictingRecord);
        var expected = input.id() == null ? input.operationId().toString() : input.id();
        var record = result.path("registro");
        if (!input.operationId().toString().equals(result.path("operationId").stringValue(null))
                || !expected.equals(record.path("id").stringValue(null))
                || !record.path("version").isString()) throw ApiException.invalidResponse();
        return result;
    }
    private static boolean positive(String value) {
        return value != null && new java.math.BigDecimal(value).signum() > 0;
    }
}
