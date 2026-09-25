package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.HandoverStatus;

import java.time.Instant;

public record HandoverResponse(
        Long id,
        String fromHolder,
        String toHolder,
        HandoverStatus status,
        Instant initiatedAt,
        Instant confirmedAt) {

    public static HandoverResponse from(HandoverRecord record) {
        return new HandoverResponse(
                record.getId(),
                record.getFromHolder(),
                record.getToHolder(),
                record.getStatus(),
                record.getInitiatedAt(),
                record.getConfirmedAt());
    }
}
