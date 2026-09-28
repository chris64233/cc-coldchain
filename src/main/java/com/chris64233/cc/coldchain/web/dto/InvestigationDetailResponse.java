package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import com.chris64233.cc.coldchain.domain.ConclusionRecord;
import com.chris64233.cc.coldchain.domain.DispositionRecord;
import com.chris64233.cc.coldchain.domain.InvestigationDecision;
import com.chris64233.cc.coldchain.service.ExcursionCalculator;
import com.chris64233.cc.coldchain.service.InvestigationService.InvestigationDetail;

import java.time.Instant;
import java.util.List;

/**
 * 调查详情聚合：锁定的异常区间、最新证据区间、证据版本、结论历史、审批链、
 * 处置事件、各单元当前状态与完整温度时间线。
 */
public record InvestigationDetailResponse(
        InvestigationResponse investigation,
        List<IntervalDto> lockedExcursionIntervals,
        List<IntervalDto> liveExcursionIntervals,
        boolean evidenceExpanded,
        List<ConclusionDto> conclusions,
        List<ApprovalDto> approvalChain,
        List<DispositionDto> dispositions,
        List<PackagingUnitResponse> units,
        List<TemperatureEventResponse> temperatureTimeline) {

    public static InvestigationDetailResponse from(InvestigationDetail detail) {
        List<IntervalDto> locked = detail.lockedIntervals().stream()
                .map(IntervalDto::from).toList();
        List<IntervalDto> live = detail.liveEvidence().intervals().stream()
                .map(IntervalDto::from).toList();
        boolean expanded = live.size() > locked.size();
        for (int i = 0; !expanded && i < Math.min(locked.size(), live.size()); i++) {
            IntervalDto oldInterval = locked.get(i);
            IntervalDto nowInterval = live.get(i);
            if (nowInterval.start().isBefore(oldInterval.start())
                    || nowInterval.end().isAfter(oldInterval.end())) {
                expanded = true;
            }
        }
        return new InvestigationDetailResponse(
                InvestigationResponse.from(detail.investigation()),
                locked,
                live,
                expanded,
                detail.conclusions().stream().map(ConclusionDto::from).toList(),
                detail.approvals().stream().map(ApprovalDto::from).toList(),
                detail.dispositions().stream().map(DispositionDto::from).toList(),
                detail.units().stream().map(PackagingUnitResponse::from).toList(),
                detail.temperatureTimeline().stream().map(TemperatureEventResponse::from).toList());
    }

    public record IntervalDto(Instant start, Instant end, List<String> eventIds) {
        static IntervalDto from(ExcursionCalculator.Interval interval) {
            return new IntervalDto(interval.start(), interval.end(), interval.eventIds());
        }
    }

    public record ConclusionDto(
            long decisionSeq,
            InvestigationDecision decision,
            List<String> unitCodes,
            long evidenceVersion,
            String decidedBy,
            Instant validUntil,
            String usageRestrictions,
            Instant createdAt) {

        static ConclusionDto from(ConclusionRecord record) {
            return new ConclusionDto(
                    record.getDecisionSeq(),
                    record.getDecision(),
                    InvestigationResponse.splitCsv(record.getUnitCodes()),
                    record.getEvidenceVersion(),
                    record.getDecidedBy(),
                    record.getValidUntil(),
                    record.getUsageRestrictions(),
                    record.getCreatedAt());
        }
    }

    public record ApprovalDto(
            Long id,
            String approvalNo,
            String approver,
            String role,
            long decisionSeq,
            long evidenceVersion,
            Instant createdAt) {

        static ApprovalDto from(ApprovalRecord record) {
            return new ApprovalDto(
                    record.getId(),
                    record.getApprovalNo(),
                    record.getApprover(),
                    record.getRole(),
                    record.getDecisionSeq(),
                    record.getEvidenceVersion(),
                    record.getCreatedAt());
        }
    }

    public record DispositionDto(
            Long id,
            String dispositionNo,
            String type,
            String unitCode,
            long evidenceVersion,
            long decisionSeq,
            String actedBy,
            Instant validUntil,
            String usageRestrictions,
            Instant createdAt) {

        static DispositionDto from(DispositionRecord record) {
            return new DispositionDto(
                    record.getId(),
                    record.getDispositionNo(),
                    record.getType().name(),
                    record.getUnitCode(),
                    record.getEvidenceVersion(),
                    record.getDecisionSeq(),
                    record.getActedBy(),
                    record.getValidUntil(),
                    record.getUsageRestrictions(),
                    record.getCreatedAt());
        }
    }
}
