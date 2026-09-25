package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.HandoverStatus;

import java.time.Instant;

public record HandoverResponse(
        Long id,
        String fromHolder,
        String toHolder,
        Instant initiatedAt,
        Instant confirmedAt,
        HandoverStatus status) {

    public static HandoverResponse from(HandoverRecord record) {
        return new HandoverResponse(
                record.getId(),
                record.getFromHolder(),
                record.getToHolder(),
                record.getInitiatedAt(),
                record.getConfirmedAt(),
                record.getStatus());
    }
}
