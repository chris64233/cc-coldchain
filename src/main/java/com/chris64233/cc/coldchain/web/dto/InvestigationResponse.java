package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.DispositionType;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;

import java.time.Instant;
import java.util.List;

public record InvestigationResponse(
        Long id,
        String investigationNo,
        InvestigationStatus status,
        int evidenceVersion,
        boolean evidenceCurrent,
        List<BreachIntervalResponse> lockedBreachIntervals,
        ThresholdConfig threshold,
        String lockedHolder,
        String reason,
        DispositionType dispositionType,
        String usageRestrictions,
        Instant validUntil,
        List<String> unitCodes,
        Instant createdAt,
        Instant concludedAt,
        Instant completedAt,
        Instant supersededAt,
        String supersedeReason) {

    public record ThresholdConfig(double tempMin, double tempMax, long maxBreachSeconds) {
    }

    public static InvestigationResponse summary(Investigation investigation) {
        return build(investigation, null);
    }

    public static InvestigationResponse from(Investigation investigation, Integer currentEvidenceVersion) {
        return build(investigation, currentEvidenceVersion);
    }

    private static InvestigationResponse build(Investigation investigation, Integer currentEvidenceVersion) {
        List<String> codes = investigation.getUnits().stream()
                .map(link -> link.getPackingUnit().getUnitCode())
                .sorted()
                .toList();
        List<BreachIntervalResponse> intervals =
                com.chris64233.cc.coldchain.domain.BreachInterval.parse(investigation.getBreachSnapshot()).stream()
                        .map(BreachIntervalResponse::from)
                        .toList();
        boolean evidenceCurrent = currentEvidenceVersion == null
                || currentEvidenceVersion == investigation.getEvidenceVersion();
        return new InvestigationResponse(
                investigation.getId(),
                investigation.getInvestigationNo(),
                investigation.getStatus(),
                investigation.getEvidenceVersion(),
                evidenceCurrent,
                intervals,
                new ThresholdConfig(investigation.getLockedTempMin(), investigation.getLockedTempMax(),
                        investigation.getLockedMaxBreachSeconds()),
                investigation.getLockedHolder(),
                investigation.getReason(),
                investigation.getDispositionType(),
                investigation.getUsageRestrictions(),
                investigation.getValidUntil(),
                codes,
                investigation.getCreatedAt(),
                investigation.getConcludedAt(),
                investigation.getCompletedAt(),
                investigation.getSupersededAt(),
                investigation.getSupersedeReason());
    }
}
