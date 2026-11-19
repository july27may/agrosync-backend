package com.agrosync.backend.fincas.dto;

import java.util.List;

public record FincaPage(List<Finca> items, int page, int size, int recordsOnPage, Integer totalPages) {
    public FincaPage {
        items = List.copyOf(items);
    }
}
