package com.chris64233.cc.coldchain.service;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import com.chris64233.cc.coldchain.domain.BreachInterval;
import com.chris64233.cc.coldchain.domain.DispositionEvent;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.PackingUnit;
import com.chris64233.cc.coldchain.domain.TemperatureEvent;

import java.util.List;

/**
 * 调查详情聚合视图：异常区间、证据版本、审批链、各覆盖单元当前状态、
 * 锁定的证据事件与运单完整温度时间线。
 */
public record InvestigationView(
        Investigation investigation,
        int currentEvidenceVersion,
        List<PackingUnit> units,
        List<ApprovalRecord> approvals,
        List<DispositionEvent> dispositionEvents,
        List<TemperatureEvent> evidenceEvents,
        List<TemperatureEvent> temperatureTimeline,
        List<BreachInterval> currentBreachIntervals) {
}
