package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.PackingUnit;
import com.chris64233.cc.coldchain.domain.UnitStatus;

import java.time.Instant;

public record PackingUnitResponse(
        Long id,
        String unitCode,
        UnitStatus status,
        Instant registeredAt) {

    public static PackingUnitResponse from(PackingUnit unit) {
        return new PackingUnitResponse(unit.getId(), unit.getUnitCode(), unit.getStatus(), unit.getRegisteredAt());
    }
}
