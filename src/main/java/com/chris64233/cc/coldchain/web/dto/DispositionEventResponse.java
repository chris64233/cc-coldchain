package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.DispositionEvent;
import com.chris64233.cc.coldchain.domain.DispositionType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public record DispositionEventResponse(
        Long id,
        String dispositionEventId,
        DispositionEvent.Stage stage,
        DispositionType dispositionType,
        List<String> unitCodes,
        String usageRestrictions,
        Instant validUntil,
        String operator,
        Instant eventTime) {

    public static DispositionEventResponse from(DispositionEvent event) {
        List<String> codes = event.getUnitCodes() == null || event.getUnitCodes().isBlank()
                ? List.of()
                : Arrays.stream(event.getUnitCodes().split(",")).toList();
        return new DispositionEventResponse(
                event.getId(),
                event.getDispositionEventId(),
                event.getStage(),
                event.getDispositionType(),
                codes,
                event.getUsageRestrictions(),
                event.getValidUntil(),
                event.getOperator(),
                event.getEventTime());
    }
}
