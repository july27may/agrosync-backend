package com.agrosync.backend.cosechas;

import com.agrosync.backend.auth.FarmAccess;
import com.agrosync.backend.auth.SuiteCrmIdentity;
import com.agrosync.backend.shared.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

@RestController
public class CosechasController {
    private static final String CRM_ID = "[A-Za-z0-9_-]{1,36}";
    private static final long LIMIT = 99_999_999_999_999L;

    private final FarmAccess access;
    private final SuiteCrmIdentity identity;

    public CosechasController(FarmAccess access, SuiteCrmIdentity identity) {
        this.access = access;
        this.identity = identity;
    }

    record Capture(@NotNull UUID operationId,
                   @NotNull @Pattern(regexp = CRM_ID) String fincaId,
                   @NotNull @Pattern(regexp = CRM_ID) String loteId,
                   @NotNull @Pattern(regexp = CRM_ID) String recolectorId,
                   @NotNull LocalDate fecha,
                   @NotNull @Positive @Max(LIMIT) Long pesoGramos,
                   @NotNull @Positive @Max(LIMIT) Long tarifaCentavos,
                   @NotNull @Pattern(regexp = "cereza|pergamino") String estadoCafe) {}

    record Confirmation(String operationId, String crmId) {}

    @GetMapping("/api/v1/cosechas")
    JsonNode weekly(@RequestParam @Pattern(regexp = CRM_ID) String fincaId,
                    @RequestParam LocalDate semana, Principal principal) {
        if (semana.getDayOfWeek() != DayOfWeek.MONDAY || semana.getYear() < 1 || semana.getYear() > 9999) {
            throw ApiException.invalidRequest();
        }
        access.requireFarm(principal, fincaId);
        return identity.get(principal, uri -> uri.path("/Api/V8/custom/agrosync/cosechas")
                .queryParam("fincaId", fincaId).queryParam("semana", semana.toString()).build());
    }

    @PostMapping("/api/v1/cosechas/sync")
    Confirmation sync(@RequestBody @Valid Capture capture, Principal principal) {
        var user = access.current(principal);
        user.requireFarm(capture.fincaId());
        if (user.permisos().get(capture.fincaId()) == FarmAccess.Role.CONSULTA) {
            throw ApiException.forbidden();
        }
        var document = identity.createHarvest(principal, capture);
        var expected = capture.operationId().toString();
        var operationId = document.path("operationId").stringValue(null);
        var crmId = document.path("crmId").stringValue(null);
        if (!expected.equals(operationId) || !expected.equals(crmId)) {
            throw ApiException.invalidResponse();
        }
        return new Confirmation(operationId, crmId);
    }
}
