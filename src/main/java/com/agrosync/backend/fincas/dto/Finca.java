package com.agrosync.backend.fincas.dto;

import java.math.BigDecimal;

public record Finca(
        String id, String nombre, String codigo, String estado, BigDecimal areaHa,
        String departamentoId, String municipioId, String veredaId, String propietarioId
) {}
