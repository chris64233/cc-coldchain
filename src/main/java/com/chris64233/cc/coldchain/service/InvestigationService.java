package com.chris64233.cc.coldchain.service;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import com.chris64233.cc.coldchain.domain.BreachInterval;
import com.chris64233.cc.coldchain.domain.DispositionEvent;
import com.chris64233.cc.coldchain.domain.DispositionType;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
import com.chris64233.cc.coldchain.domain.PackingUnit;
import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.domain.TemperatureEvent;
import com.chris64233.cc.coldchain.domain.UnitStatus;
import com.chris64233.cc.coldchain.repository.ApprovalRecordRepository;
import com.chris64233.cc.coldchain.repository.DispositionEventRepository;
import com.chris64233.cc.coldchain.repository.InvestigationRepository;
import com.chris64233.cc.coldchain.repository.PackingUnitRepository;
import com.chris64233.cc.coldchain.repository.TemperatureEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 温度异常调查、受控放行与影响批次（包装单元）追踪。
 * 所有写操作都先对运单行加悲观写锁，与温度事件写入、交接串行化。
 */
@Service
public class InvestigationService {

    private final ColdChainService coldChainService;
    private final InvestigationRepository investigationRepository;
    private final PackingUnitRepository packingUnitRepository;
    private final TemperatureEventRepository temperatureEventRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final DispositionEventRepository dispositionEventRepository;

    public InvestigationService(ColdChainService coldChainService,
                                InvestigationRepository investigationRepository,
                                PackingUnitRepository packingUnitRepository,
                                TemperatureEventRepository temperatureEventRepository,
                                ApprovalRecordRepository approvalRecordRepository,
                                DispositionEventRepository dispositionEventRepository) {
        this.coldChainService = coldChainService;
        this.investigationRepository = investigationRepository;
        this.packingUnitRepository = packingUnitRepository;
        this.temperatureEventRepository = temperatureEventRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.dispositionEventRepository = dispositionEventRepository;
    }

    @Transactional
    public Investigation createInvestigation(String waybillNo, String investigationNo,
                                             List<String> targetUnitCodes, String reason) {
        Shipment shipment = coldChainService.lockShipment(waybillNo);
        var duplicate = investigationRepository.findByInvestigationNo(investigationNo);
        if (duplicate.isPresent()) {
            Investigation existing = duplicate.get();
            if (!existing.getShipment().getId().equals(shipment.getId())) {
                throw ApiException.conflict("DUPLICATE_INVESTIGATION_NO", "调查号已存在: " + investigationNo);
            }
            if (coveredUnitCodes(existing).equals(resolveRequestCodes(shipment, targetUnitCodes))) {
                return existing;
            }
            throw ApiException.conflict("INVESTIGATION_NO_CONFLICT",
                    "调查号 " + investigationNo + " 已存在但覆盖单元不一致");
        }
        if (shipment.getStatus() != ShipmentStatus.QUARANTINED) {
            throw ApiException.conflict("SHIPMENT_NOT_QUARANTINED", "只有隔离货物才能创建调查");
        }
        List<PackingUnit> targets = resolveTargets(shipment, targetUnitCodes);
        if (targets.isEmpty()) {
            throw ApiException.conflict("NO_QUARANTINED_UNITS", "没有可调查的隔离包装单元");
        }
        assertNoOpenInvestigationCovers(shipment, targets);

        Investigation investigation = new Investigation(investigationNo, shipment,
                shipment.getEvidenceVersion(), shipment.getBreachSnapshot(), reason);
        targets.forEach(investigation::addUnit);

        // 锁定触发隔离的温度事件集合：当前已确认异常区间内的越界样本。
        List<TemperatureEvent> allEvents =
                temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId());
        List<BreachCalculator.BreachSegment> segments = BreachCalculator.compute(
                allEvents, shipment.getTempMin(), shipment.getTempMax(), shipment.getMaxBreachSeconds());
        Set<Long> lockedEventIds = segments.stream()
                .filter(BreachCalculator.BreachSegment::quarantineTrigger)
                .flatMap(segment -> segment.breachSamples().stream())
                .map(TemperatureEvent::getId)
                .collect(Collectors.toSet());
        allEvents.stream()
                .filter(event -> lockedEventIds.contains(event.getId()))
                .forEach(investigation::addEvidenceEvent);

        return investigationRepository.saveAndFlush(investigation);
    }

    @Transactional
    public Investigation conclude(String waybillNo, String investigationNo, String dispositionEventId,
                                  DispositionType type, Instant validUntil, String usageRestrictions,
                                  String operator) {
        Shipment shipment = coldChainService.lockShipment(waybillNo);
        Investigation investigation = loadInvestigation(shipment, investigationNo);

        var replay = dispositionEventRepository
                .findByInvestigationIdAndDispositionEventId(investigation.getId(), dispositionEventId);
        if (replay.isPresent()) {
            DispositionEvent event = replay.get();
            if (event.getDispositionType() == type && event.getStage() == DispositionEvent.Stage.CONCLUSION) {
                return investigation;
            }
            throw ApiException.conflict("DISPOSITION_CONFLICT",
                    "处置事件号 " + dispositionEventId + " 已存在但内容不一致");
        }

        if (investigation.getStatus() == InvestigationStatus.SUPERSEDED
                || shipment.getEvidenceVersion() != investigation.getEvidenceVersion()) {
            throw ApiException.conflict("INVESTIGATION_SUPERSEDED",
                    "异常区间已扩大，原调查结论失效，请基于新证据重新创建调查");
        }
        if (investigation.getStatus() == InvestigationStatus.COMPLETED) {
            throw ApiException.conflict("INVESTIGATION_ALREADY_COMPLETED", "调查已完成，不能重复判定");
        }
        if (investigation.getStatus() == InvestigationStatus.AWAITING_APPROVAL) {
            throw ApiException.conflict("CONCLUSION_ALREADY_SUBMITTED",
                    "已判定有条件放行且待审批，不能再次判定");
        }

        List<PackingUnit> units = coveredUnits(investigation);
        String unitCodes = joinUnitCodes(units);
        Instant now = Instant.now();

        switch (type) {
            case DESTROY -> {
                assertAllQuarantined(units);
                units.forEach(unit -> unit.setStatus(UnitStatus.DESTROYED));
                investigation.conclude(type, null, null, now);
            }
            case CONTINUE_QUARANTINE -> {
                assertAllQuarantined(units);
                investigation.conclude(type, null, null, now);
            }
            case CONDITIONAL_RELEASE -> {
                if (validUntil == null || !validUntil.isAfter(now)) {
                    throw ApiException.badRequest("INVALID_VALID_UNTIL",
                            "有条件放行必须提供晚于当前时间的有效期限");
                }
                if (usageRestrictions == null || usageRestrictions.isBlank()) {
                    throw ApiException.badRequest("MISSING_USAGE_RESTRICTIONS",
                            "有条件放行必须记录使用限制");
                }
                assertAllQuarantined(units);
                investigation.conclude(type, usageRestrictions.strip(), validUntil, now);
            }
        }
        investigation.getDispositionEvents().add(new DispositionEvent(
                investigation, dispositionEventId, DispositionEvent.Stage.CONCLUSION,
                type, unitCodes, investigation.getUsageRestrictions(), investigation.getValidUntil(),
                operator, now));
        return investigationRepository.saveAndFlush(investigation);
    }

    @Transactional
    public ApprovalRecord approve(String waybillNo, String investigationNo, String approvalEventId,
                                  String approver, String role) {
        Shipment shipment = coldChainService.lockShipment(waybillNo);
        Investigation investigation = loadInvestigation(shipment, investigationNo);

        var replay = approvalRecordRepository
                .findByInvestigationIdAndApprovalEventId(investigation.getId(), approvalEventId);
        if (replay.isPresent()) {
            ApprovalRecord existing = replay.get();
            if (existing.getApprover().equals(approver) && existing.getRole().equals(role)) {
                return existing;
            }
            throw ApiException.conflict("APPROVAL_CONFLICT",
                    "审批事件号 " + approvalEventId + " 已存在但审批人或角色不一致");
        }

        if (investigation.getStatus() == InvestigationStatus.SUPERSEDED
                || shipment.getEvidenceVersion() != investigation.getEvidenceVersion()) {
            throw ApiException.conflict("INVESTIGATION_SUPERSEDED", "调查结论已失效，不能继续审批");
        }
        if (investigation.getStatus() != InvestigationStatus.AWAITING_APPROVAL) {
            throw ApiException.conflict("INVALID_INVESTIGATION_STATE", "调查不处于待审批状态");
        }
        if (investigation.getApprovals().size() >= 2) {
            throw ApiException.conflict("APPROVAL_COMPLETE", "两名审批人已完成审批");
        }
        for (ApprovalRecord existing : investigation.getApprovals()) {
            if (existing.getApprover().equals(approver)) {
                throw ApiException.conflict("APPROVAL_DUPLICATE_APPROVER",
                        "同一审批人不能重复审批，需要两名不同角色的审批");
            }
            if (existing.getRole().equals(role)) {
                throw ApiException.conflict("APPROVAL_DUPLICATE_ROLE",
                        "两名审批人必须来自不同角色");
            }
        }
        ApprovalRecord record = approvalRecordRepository.saveAndFlush(
                new ApprovalRecord(investigation, approvalEventId, approver, role, Instant.now()));
        investigation.getApprovals().add(record);
        investigationRepository.saveAndFlush(investigation);
        return record;
    }

    @Transactional
    public Investigation executeRelease(String waybillNo, String investigationNo, String dispositionEventId,
                                        String operator) {
        Shipment shipment = coldChainService.lockShipment(waybillNo);
        Investigation investigation = loadInvestigation(shipment, investigationNo);

        var replay = dispositionEventRepository
                .findByInvestigationIdAndDispositionEventId(investigation.getId(), dispositionEventId);
        if (replay.isPresent()) {
            DispositionEvent event = replay.get();
            if (event.getStage() == DispositionEvent.Stage.EXECUTION
                    && event.getDispositionType() == DispositionType.CONDITIONAL_RELEASE) {
                return investigation;
            }
            throw ApiException.conflict("DISPOSITION_CONFLICT",
                    "处置事件号 " + dispositionEventId + " 已存在但内容不一致");
        }

        // 隔离解除与新越界事件并发：证据版本变化即不得放行仍不满足条件的货物。
        if (shipment.getEvidenceVersion() != investigation.getEvidenceVersion()) {
            throw ApiException.conflict("NEW_BREACH_AFTER_APPROVAL",
                    "审批后出现新的越界事件，异常区间已扩大，不得放行，需重新评估");
        }
        if (investigation.getStatus() == InvestigationStatus.SUPERSEDED) {
            throw ApiException.conflict("INVESTIGATION_SUPERSEDED",
                    "调查结论已失效，需重新评估");
        }
        if (investigation.getStatus() != InvestigationStatus.AWAITING_APPROVAL) {
            throw ApiException.conflict("INVALID_INVESTIGATION_STATE", "调查不处于待放行状态");
        }
        if (investigation.getApprovals().size() < 2) {
            throw ApiException.conflict("APPROVAL_INCOMPLETE", "有条件放行需要两名不同角色的审批");
        }
        Instant now = Instant.now();
        if (investigation.getValidUntil() == null || !investigation.getValidUntil().isAfter(now)) {
            throw ApiException.conflict("RELEASE_EXPIRED", "放行有效期已过，需重新判定");
        }
        List<PackingUnit> units = coveredUnits(investigation);
        assertAllQuarantined(units);

        units.forEach(unit -> unit.setStatus(UnitStatus.RELEASED));
        investigation.complete(now);
        investigation.getDispositionEvents().add(new DispositionEvent(
                investigation, dispositionEventId, DispositionEvent.Stage.EXECUTION,
                DispositionType.CONDITIONAL_RELEASE, joinUnitCodes(units),
                investigation.getUsageRestrictions(), investigation.getValidUntil(), operator, now));
        return investigationRepository.saveAndFlush(investigation);
    }

    @Transactional(readOnly = true)
    public InvestigationView getInvestigationView(String waybillNo, String investigationNo) {
        Shipment shipment = coldChainService.getShipment(waybillNo);
        Investigation investigation = loadInvestigation(shipment, investigationNo);
        List<String> unitCodes = investigation.getUnits().stream()
                .map(link -> link.getPackingUnit().getUnitCode())
                .sorted()
                .toList();
        List<PackingUnit> units = packingUnitRepository
                .findByShipmentIdAndUnitCodeIn(shipment.getId(), unitCodes).stream()
                .sorted((a, b) -> a.getUnitCode().compareTo(b.getUnitCode()))
                .toList();
        List<TemperatureEvent> evidenceEvents = investigation.getEvidenceEvents().stream()
                .map(link -> link.getTemperatureEvent())
                .sorted((a, b) -> a.getSampledAt().compareTo(b.getSampledAt()))
                .toList();
        List<TemperatureEvent> timeline =
                temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId());
        List<BreachInterval> currentIntervals = BreachCalculator.confirmedIntervals(
                BreachCalculator.compute(timeline, shipment.getTempMin(), shipment.getTempMax(),
                        shipment.getMaxBreachSeconds()));
        return new InvestigationView(investigation, shipment.getEvidenceVersion(),
                List.copyOf(units),
                List.copyOf(investigation.getApprovals()), List.copyOf(investigation.getDispositionEvents()),
                evidenceEvents, timeline, currentIntervals);
    }

    @Transactional(readOnly = true)
    public List<Investigation> listInvestigations(String waybillNo) {
        Shipment shipment = coldChainService.getShipment(waybillNo);
        return investigationRepository.findByShipmentIdOrderByIdAsc(shipment.getId());
    }

    /**
     * 晚到历史事件扩大异常区间时，由温度事件写入流程在同一事务内调用：
     * 尚未完成（含待审批）的调查结论与审批失效，必须重新评估。
     */
    @Transactional
    public void supersedeUnfinishedInvestigations(Shipment shipment, int newEvidenceVersion, String reason) {
        List<Investigation> unfinished = investigationRepository.findByShipmentIdAndStatusInOrderByIdAsc(
                shipment.getId(), List.of(InvestigationStatus.OPEN, InvestigationStatus.AWAITING_APPROVAL));
        Instant now = Instant.now();
        for (Investigation investigation : unfinished) {
            investigation.supersede(reason, now);
        }
    }

    private Investigation loadInvestigation(Shipment shipment, String investigationNo) {
        Investigation investigation = investigationRepository.findByInvestigationNo(investigationNo)
                .orElseThrow(() -> ApiException.notFound("调查不存在: " + investigationNo));
        if (!investigation.getShipment().getId().equals(shipment.getId())) {
            throw ApiException.notFound("调查不存在: " + investigationNo);
        }
        return investigation;
    }

    private Set<String> resolveRequestCodes(Shipment shipment, List<String> targetUnitCodes) {
        if (targetUnitCodes == null || targetUnitCodes.isEmpty()) {
            return packingUnitRepository.findByShipmentIdOrderByIdAsc(shipment.getId()).stream()
                    .filter(unit -> unit.getStatus() == UnitStatus.QUARANTINED)
                    .map(PackingUnit::getUnitCode)
                    .collect(Collectors.toCollection(HashSet::new));
        }
        return new HashSet<>(targetUnitCodes);
    }

    private List<PackingUnit> resolveTargets(Shipment shipment, List<String> targetUnitCodes) {
        if (targetUnitCodes == null || targetUnitCodes.isEmpty()) {
            return packingUnitRepository.findByShipmentIdOrderByIdAsc(shipment.getId()).stream()
                    .filter(unit -> unit.getStatus() == UnitStatus.QUARANTINED)
                    .toList();
        }
        List<PackingUnit> found = packingUnitRepository
                .findByShipmentIdAndUnitCodeIn(shipment.getId(), targetUnitCodes);
        Set<String> foundCodes = found.stream().map(PackingUnit::getUnitCode).collect(Collectors.toSet());
        for (String code : targetUnitCodes) {
            if (!foundCodes.contains(code)) {
                throw ApiException.badRequest("UNIT_NOT_FOUND", "包装单元不存在: " + code);
            }
        }
        for (PackingUnit unit : found) {
            if (unit.getStatus() != UnitStatus.QUARANTINED) {
                throw ApiException.conflict("UNIT_NOT_QUARANTINED",
                        "包装单元 " + unit.getUnitCode() + " 不处于隔离状态，不能纳入调查");
            }
        }
        return found.stream()
                .sorted((a, b) -> a.getUnitCode().compareTo(b.getUnitCode()))
                .toList();
    }

    private void assertNoOpenInvestigationCovers(Shipment shipment, List<PackingUnit> targets) {
        List<Investigation> open = investigationRepository.findByShipmentIdAndStatusInOrderByIdAsc(
                shipment.getId(), List.of(InvestigationStatus.OPEN, InvestigationStatus.AWAITING_APPROVAL));
        Set<Long> coveredIds = open.stream()
                .flatMap(investigation -> investigation.getUnits().stream())
                .map(link -> link.getPackingUnit().getId())
                .collect(Collectors.toSet());
        for (PackingUnit unit : targets) {
            if (coveredIds.contains(unit.getId())) {
                throw ApiException.conflict("INVESTIGATION_PENDING_UNIT",
                        "包装单元 " + unit.getUnitCode() + " 已存在未完成的调查");
            }
        }
    }

    private void assertAllQuarantined(List<PackingUnit> units) {
        for (PackingUnit unit : units) {
            if (unit.getStatus() != UnitStatus.QUARANTINED) {
                throw ApiException.conflict("UNIT_NOT_QUARANTINED",
                        "包装单元 " + unit.getUnitCode() + " 当前状态为 " + unit.getStatus()
                                + "，不能重复处置（同一单元不得同时放行与报废）");
            }
        }
    }

    private List<PackingUnit> coveredUnits(Investigation investigation) {
        return investigation.getUnits().stream()
                .map(link -> link.getPackingUnit())
                .sorted((a, b) -> a.getUnitCode().compareTo(b.getUnitCode()))
                .toList();
    }

    private Set<String> coveredUnitCodes(Investigation investigation) {
        return coveredUnits(investigation).stream().map(PackingUnit::getUnitCode).collect(Collectors.toSet());
    }

    private String joinUnitCodes(List<PackingUnit> units) {
        return units.stream().map(PackingUnit::getUnitCode).collect(Collectors.joining(","));
    }
}
