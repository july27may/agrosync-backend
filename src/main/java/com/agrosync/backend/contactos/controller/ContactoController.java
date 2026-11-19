package com.agrosync.backend.contactos.controller;

import com.agrosync.backend.auth.FarmAccess;
import com.agrosync.backend.auth.SuiteCrmIdentity;
import java.security.Principal;
import com.agrosync.backend.contactos.dto.Contacto;
import com.agrosync.backend.contactos.integration.SuiteCrmContactos;
import com.agrosync.backend.integration.suitecrm.SuiteCrmCatalog;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContactoController {
    private final SuiteCrmContactos contactos;

    private final FarmAccess access;
    private final SuiteCrmIdentity identity;

    public ContactoController(SuiteCrmContactos contactos, FarmAccess access, SuiteCrmIdentity identity) {
        this.identity = identity;
        this.access = access;
        this.contactos = contactos;
    }

    @GetMapping("/api/v1/contactos")
    public SuiteCrmCatalog.Page<Contacto> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, Principal principal) {
        access.requireAssignment(principal);
        return contactos.list(page, size, uri -> identity.get(principal, uri));
    }
}
