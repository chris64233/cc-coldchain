package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationDecision;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public record InvestigationResponse(
        Long id,
        String investigationNo,
        String waybillNo,
        InvestigationStatus status,
        InvestigationDecision decision,
        long decisionSeq,
        long evidenceVersion,
        String custodian,
        double thresholdTempMin,
        double thresholdTempMax,
        long thresholdMaxBreachSeconds,
        Instant excursionStart,
        Instant excursionEnd,
        List<String> triggerEventIds,
        List<String> decisionUnitCodes,
        Instant validUntil,
        String usageRestrictions,
        Instant createdAt,
        Instant decidedAt,
        Instant invalidatedAt,
        String invalidationReason,
        Instant completedAt) {

    public static InvestigationResponse from(Investigation investigation) {
        return new InvestigationResponse(
                investigation.getId(),
                investigation.getInvestigationNo(),
                investigation.getWaybillNo(),
                investigation.getStatus(),
                investigation.getDecision(),
                investigation.getDecisionSeq(),
                investigation.getEvidenceVersion(),
                investigation.getCustodian(),
                investigation.getThresholdTempMin(),
                investigation.getThresholdTempMax(),
                investigation.getThresholdMaxBreachSeconds(),
                investigation.getExcursionStart(),
                investigation.getExcursionEnd(),
                splitCsv(investigation.getTriggerEventIds()),
                splitCsv(investigation.getDecisionUnitCodes()),
                investigation.getValidUntil(),
                investigation.getUsageRestrictions(),
                investigation.getCreatedAt(),
                investigation.getDecidedAt(),
                investigation.getInvalidatedAt(),
                investigation.getInvalidationReason(),
                investigation.getCompletedAt());
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).filter(s -> !s.isBlank()).toList();
    }
}
