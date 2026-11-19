package com.agrosync.backend.fincas.service;

import com.agrosync.backend.fincas.dto.Finca;
import com.agrosync.backend.fincas.dto.FincaPage;
import com.agrosync.backend.fincas.integration.SuiteCrmFincaClient;
import org.springframework.stereotype.Service;

@Service
public class FincaService {
    private final SuiteCrmFincaClient crm;

    public FincaService(SuiteCrmFincaClient crm) {
        this.crm = crm;
    }

    public FincaPage list(int page, int size) {
        return crm.list(page, size);
    }

    public Finca find(String id) {
        return crm.find(id);
    }
}
