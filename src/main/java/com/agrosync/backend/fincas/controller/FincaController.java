package com.agrosync.backend.fincas.controller;

import com.agrosync.backend.auth.FarmAccess;
import java.security.Principal;
import com.agrosync.backend.fincas.dto.Finca;
import com.agrosync.backend.fincas.dto.FincaPage;
import com.agrosync.backend.fincas.service.FincaService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fincas")
public class FincaController {
    private final FincaService service;

    private final FarmAccess access;

    public FincaController(FincaService service, FarmAccess access) {
        this.access = access;
        this.service = service;
    }

    @GetMapping
    public FincaPage list(@RequestParam(defaultValue = "1") @Min(1) int page,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, Principal principal) {
        var ids = access.farms(principal);
        int start = (int) Math.min((long) (page - 1) * size, ids.size());
        int end = Math.min(start + size, ids.size());
        var items = ids.subList(start, end).stream().map(service::find).toList();
        return new FincaPage(items, page, size, items.size(), (ids.size() + size - 1) / size);
    }

    @GetMapping("/{id}")
    public Finca find(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id, Principal principal) {
        access.requireFarm(principal, id);
        return service.find(id);
    }
}
