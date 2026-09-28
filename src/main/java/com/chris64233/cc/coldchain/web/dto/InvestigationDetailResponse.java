package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.service.InvestigationView;

import java.util.List;

/**
 * 调查详情：调查主体 + 各覆盖单元当前状态 + 审批链 + 处置事件
 * + 锁定证据事件 + 当前异常区间 + 完整温度时间线。
 */
public record InvestigationDetailResponse(
        InvestigationResponse investigation,
        List<PackingUnitResponse> units,
        List<ApprovalResponse> approvalChain,
        List<DispositionEventResponse> dispositionEvents,
        List<TemperatureEventResponse> evidenceEvents,
        List<BreachIntervalResponse> currentBreachIntervals,
        List<TemperatureEventResponse> temperatureTimeline) {

    public static InvestigationDetailResponse from(InvestigationView view) {
        return new InvestigationDetailResponse(
                InvestigationResponse.from(view.investigation(), view.currentEvidenceVersion()),
                view.units().stream().map(PackingUnitResponse::from).toList(),
                view.approvals().stream().map(ApprovalResponse::from).toList(),
                view.dispositionEvents().stream().map(DispositionEventResponse::from).toList(),
                view.evidenceEvents().stream().map(TemperatureEventResponse::from).toList(),
                view.currentBreachIntervals().stream().map(BreachIntervalResponse::from).toList(),
                view.temperatureTimeline().stream().map(TemperatureEventResponse::from).toList());
    }
}
