package com.agrosync.backend.lotes.controller;

import com.agrosync.backend.auth.FarmAccess;
import java.security.Principal;
import com.agrosync.backend.lotes.integration.SuiteCrmLotes;
import com.agrosync.backend.lotes.dto.Lote;
import com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoteController {
    private final SuiteCrmLotes lotes;

    private final FarmAccess access;

    public LoteController(SuiteCrmLotes lotes, FarmAccess access) {
        this.access = access;
        this.lotes = lotes;
    }

    @GetMapping("/api/v1/lotes")
    public SuiteCrmCatalog.Page<Lote> list(
            @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String fincaId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, Principal principal) {
        access.requireFarm(principal, fincaId);
        return lotes.list(fincaId, page, size);
    }
}
