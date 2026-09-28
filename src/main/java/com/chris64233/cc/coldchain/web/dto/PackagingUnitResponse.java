package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.PackagingUnit;

import java.time.Instant;

public record PackagingUnitResponse(
        Long id,
        String unitCode,
        int quantity,
        String status,
        String usageRestrictions,
        Instant releasedAt,
        Instant validUntil,
        Instant createdAt) {

    public static PackagingUnitResponse from(PackagingUnit unit) {
        return new PackagingUnitResponse(
                unit.getId(),
                unit.getUnitCode(),
                unit.getQuantity(),
                unit.getStatus().name(),
                unit.getUsageRestrictions(),
                unit.getReleasedAt(),
                unit.getValidUntil(),
                unit.getCreatedAt());
    }
}
