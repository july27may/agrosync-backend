package com.agrosync.backend.ganaderia;

import com.agrosync.backend.auth.FarmAccess;
import com.agrosync.backend.auth.SuiteCrmIdentity;
import com.agrosync.backend.shared.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ganaderia")
public class GanaderiaController {
    private static final String CRM = "/Api/V8/custom/agrosync/ganaderia";
    private static final String ID = "[A-Za-z0-9_-]{1,36}";
    private final FarmAccess access;
    private final SuiteCrmIdentity crm;

    public GanaderiaController(FarmAccess access, SuiteCrmIdentity crm) {
        this.access = access;
        this.crm = crm;
    }

    @GetMapping
    JsonNode list(@RequestParam @Pattern(regexp = ID) String fincaId, Principal principal) {
        access.requireFarm(principal, fincaId);
        return crm.get(principal, uri -> uri.path(CRM).queryParam("fincaId", fincaId).build());
    }

    record Animal(@NotNull UUID operationId, @NotNull @Pattern(regexp = ID) String fincaId,
                  @NotBlank @Size(max = 30) String codigo, @NotBlank @Size(max = 255) String nombre,
                  @NotNull @Pattern(regexp = "hembra|macho") String sexo,
                  @Pattern(regexp = "ternero|ternera|novillo|novilla|vaca|toro") String categoria,
                  @PastOrPresent LocalDate nacimiento, @Pattern(regexp = ID) String madreId, Boolean registrarNacimiento,
                  @Size(max = 100) String raza,
                  @NotNull @Pattern(regexp = "activo|vendido|fallecido|inactivo") String estado) {}

    record Evento(@NotNull UUID operationId, @NotNull @Pattern(regexp = ID) String fincaId,
                  @NotNull @Pattern(regexp = ID) String animalId, @NotBlank @Size(max = 255) String nombre,
                  @NotNull LocalDate fecha,
                  @NotNull @Pattern(regexp = "vacunacion|tratamiento|revision|otro") String tipo,
                  @Size(max = 150) String producto, @Pattern(regexp = ID) String responsableId,
                  @Size(max = 100) String certificado, @Size(max = 4000) String observaciones) {}

    record Receipt(String operationId, String crmId) {}

    @PostMapping("/animales")
    Receipt animal(@Valid @RequestBody Animal input, Principal principal) {
        if (Boolean.TRUE.equals(input.registrarNacimiento()) && (input.nacimiento() == null || input.madreId() == null)) {
            throw ApiException.invalidRecord();
        }
        return submit(principal, input.fincaId(), input.operationId(), "animales", input, true);
    }

    @PostMapping("/eventos")
    Receipt event(@Valid @RequestBody Evento input, Principal principal) {
        return submit(principal, input.fincaId(), input.operationId(), "eventos", input, false);
    }

    private Receipt submit(Principal principal, String farm, UUID operation, String resource, Object body, boolean animal) {
        var user = access.current(principal);
        user.requireFarm(farm);
        var role = user.permisos().get(farm);
        if (role == FarmAccess.Role.CONSULTA || (animal && role != FarmAccess.Role.ADMINISTRADOR_FINCA)) {
            throw ApiException.forbidden();
        }
        var response = crm.post(principal, CRM + "/" + resource, body, ApiException::conflictingLivestock);
        var expected = operation.toString();
        if (!expected.equals(response.path("operationId").stringValue(null))
                || !expected.equals(response.path("crmId").stringValue(null))) throw ApiException.invalidResponse();
        return new Receipt(expected, expected);
    }
}
