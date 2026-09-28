package com.chris64233.cc.coldchain.service;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import com.chris64233.cc.coldchain.domain.ConclusionRecord;
import com.chris64233.cc.coldchain.domain.DispositionRecord;
import com.chris64233.cc.coldchain.domain.DispositionType;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationDecision;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
import com.chris64233.cc.coldchain.domain.PackagingUnit;
import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.domain.TemperatureEvent;
import com.chris64233.cc.coldchain.domain.UnitStatus;
import com.chris64233.cc.coldchain.repository.ApprovalRecordRepository;
import com.chris64233.cc.coldchain.repository.ConclusionRecordRepository;
import com.chris64233.cc.coldchain.repository.DispositionRecordRepository;
import com.chris64233.cc.coldchain.repository.InvestigationRepository;
import com.chris64233.cc.coldchain.repository.PackagingUnitRepository;
import com.chris64233.cc.coldchain.repository.ShipmentRepository;
import com.chris64233.cc.coldchain.repository.TemperatureEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class InvestigationService {

    static final String DEFAULT_UNIT_CODE = "DEFAULT";
    static final String ALL_UNITS = "*";

    /** 尚未完成、后到证据扩大时需要失效的状态。 */
    private static final List<InvestigationStatus> OPEN_STATUSES = List.of(
            InvestigationStatus.OPEN,
            InvestigationStatus.CONTINUED,
            InvestigationStatus.SCRAP_PENDING,
            InvestigationStatus.RELEASE_PENDING);

    private final ShipmentRepository shipmentRepository;
    private final TemperatureEventRepository temperatureEventRepository;
    private final PackagingUnitRepository unitRepository;
    private final InvestigationRepository investigationRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final ConclusionRecordRepository conclusionRepository;
    private final DispositionRecordRepository dispositionRepository;

    public InvestigationService(ShipmentRepository shipmentRepository,
                                TemperatureEventRepository temperatureEventRepository,
                                PackagingUnitRepository unitRepository,
                                InvestigationRepository investigationRepository,
                                ApprovalRecordRepository approvalRepository,
                                ConclusionRecordRepository conclusionRepository,
                                DispositionRecordRepository dispositionRepository) {
        this.shipmentRepository = shipmentRepository;
        this.temperatureEventRepository = temperatureEventRepository;
        this.unitRepository = unitRepository;
        this.investigationRepository = investigationRepository;
        this.approvalRepository = approvalRepository;
        this.conclusionRepository = conclusionRepository;
        this.dispositionRepository = dispositionRepository;
    }

    // ==================== 包装单元 ====================

    @Transactional
    public PackagingUnit registerUnit(String waybillNo, String unitCode, int quantity) {
        Shipment shipment = lockShipment(waybillNo);
        if (quantity <= 0) {
            throw ApiException.badRequest("INVALID_QUANTITY", "单元数量必须大于 0");
        }
        if (unitCode == null || unitCode.isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "单元编号不能为空");
        }
        if (unitRepository.existsByShipmentIdAndUnitCode(shipment.getId(), unitCode)) {
            throw ApiException.conflict("DUPLICATE_UNIT", "单元编号已存在: " + unitCode);
        }
        if (shipment.getStatus() == ShipmentStatus.RELEASED
                || shipment.getStatus() == ShipmentStatus.CONDEMNED) {
            throw ApiException.conflict("SHIPMENT_DISPOSED", "货物已处置终态，不能再变更包装单元拆分");
        }
        if (investigationRepository.existsByShipmentIdAndStatusIn(shipment.getId(), OPEN_STATUSES)) {
            throw ApiException.conflict("INVESTIGATION_IN_PROGRESS",
                    "存在未完成的调查，不能再变更包装单元拆分");
        }
        return unitRepository.saveAndFlush(new PackagingUnit(shipment, unitCode, quantity));
    }

    @Transactional(readOnly = true)
    public List<PackagingUnit> listUnits(String waybillNo) {
        Shipment shipment = getShipment(waybillNo);
        return unitRepository.findByShipmentIdOrderByIdAsc(shipment.getId());
    }

    // ==================== 调查 ====================

    @Transactional
    public Investigation createInvestigation(String waybillNo, String investigationNo) {
        Shipment shipment = lockShipment(waybillNo);
        if (shipment.getStatus() != ShipmentStatus.QUARANTINED) {
            throw ApiException.conflict("SHIPMENT_NOT_QUARANTINED", "只有隔离货物可以创建调查");
        }
        Optional<Investigation> replay = investigationRepository.findByInvestigationNo(investigationNo);
        if (replay.isPresent()) {
            if (!replay.get().getShipment().getId().equals(shipment.getId())) {
                throw ApiException.conflict("DUPLICATE_INVESTIGATION", "调查号已存在: " + investigationNo);
            }
            return replay.get();
        }
        if (investigationRepository.existsByShipmentIdAndStatusIn(shipment.getId(), OPEN_STATUSES)) {
            throw ApiException.conflict("INVESTIGATION_IN_PROGRESS", "该运单已存在未完成的调查");
        }
        ExcursionCalculator.Snapshot snapshot = currentSnapshot(shipment);
        if (snapshot.isEmpty()) {
            // 进入隔离必有达阈区间，此处为防御性检查
            throw ApiException.conflict("NO_EXCURSION_EVIDENCE", "当前不存在达阈异常区间，无法创建调查");
        }
        ensureUnits(shipment);
        Investigation investigation = new Investigation(
                investigationNo,
                shipment,
                shipment.getCurrentHolder(),
                snapshot.signature(),
                String.join(",", snapshot.triggerEventIds()),
                snapshot.earliestStart(),
                snapshot.latestEnd());
        try {
            return investigationRepository.saveAndFlush(investigation);
        } catch (DataIntegrityViolationException ex) {
            Optional<Investigation> existing = investigationRepository.findByInvestigationNo(investigationNo);
            if (existing.isPresent() && existing.get().getShipment().getId().equals(shipment.getId())) {
                return existing.get();
            }
            throw ApiException.conflict("DUPLICATE_INVESTIGATION", "调查号已存在: " + investigationNo);
        }
    }

    @Transactional(readOnly = true)
    public Investigation getInvestigation(String investigationNo) {
        return investigationRepository.findByInvestigationNo(investigationNo)
                .orElseThrow(() -> ApiException.notFound("调查不存在: " + investigationNo));
    }

    @Transactional(readOnly = true)
    public List<Investigation> listInvestigations(String waybillNo) {
        Shipment shipment = getShipment(waybillNo);
        return investigationRepository.findByShipmentIdOrderByCreatedAtAscIdAsc(shipment.getId());
    }

    /**
     * 提交判定。结论号通过“调查号 + 序号”幂等：同一调查重复提交相同判定时，
     * 必须携带相同的单元、有效期与使用限制，返回既有结论序号而不重复落库。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Investigation submitConclusion(String investigationNo, InvestigationDecision decision,
                                          List<String> unitCodes, Instant validUntil,
                                          String usageRestrictions, String decidedBy) {
        Investigation found = getInvestigation(investigationNo);
        Shipment shipment = lockShipment(found.getShipment());
        Investigation investigation = lockInvestigation(investigationNo);
        if (shipment.getStatus() == ShipmentStatus.CONDEMNED) {
            throw ApiException.conflict("SHIPMENT_CONDEMNED", "整批货物已报废，不能再提交结论");
        }
        if (investigation.getStatus() == InvestigationStatus.COMPLETED) {
            throw ApiException.conflict("INVESTIGATION_COMPLETED", "调查已完成，不能重新提交结论");
        }
        if (decision == InvestigationDecision.CONDITIONAL_RELEASE) {
            if (validUntil == null) {
                throw ApiException.badRequest("VALID_UNTIL_REQUIRED", "有条件放行必须提供有效期限");
            }
            if (!validUntil.isAfter(Instant.now())) {
                throw ApiException.badRequest("INVALID_VALID_UNTIL", "有效期限必须晚于当前时间");
            }
            if (usageRestrictions == null || usageRestrictions.isBlank()) {
                throw ApiException.badRequest("USAGE_RESTRICTIONS_REQUIRED",
                        "有条件放行必须记录使用限制");
            }
        }
        if (decision != InvestigationDecision.CONDITIONAL_RELEASE) {
            validUntil = null;
            usageRestrictions = null;
        }
        String encodedUnits = encodeUnitSelection(investigation, shipment, unitCodes);
        requireAllUnitsHeld(shipment, encodedUnits);

        ExcursionCalculator.Snapshot snapshot = currentSnapshot(shipment);
        if (evidenceExpanded(investigation, snapshot)) {
            if (investigation.getDecision() == null) {
                // OPEN 调查尚无结论：直接吸收最新证据，不产生失效记录
                investigation.refreshEvidence(snapshot.signature());
                investigationRepository.saveAndFlush(investigation);
            } else {
                investigation.invalidate("后到历史温度事件扩大了异常区间，结论失效", Instant.now());
                investigation.refreshEvidence(snapshot.signature());
                investigationRepository.saveAndFlush(investigation);
                throw ApiException.conflict("CONCLUSION_INVALIDATED",
                        "证据版本已扩大，原结论已失效，请重新评估");
            }
        }

        // 幂等重放：结论仍处于有效待执行状态、判定与条件完全相同时返回当前结论，不重复落库；
        // INVALIDATED 状态下即使判定内容相同也视为重新评估，产生新的结论序号
        boolean liveConclusion = investigation.getStatus() == InvestigationStatus.CONTINUED
                || investigation.getStatus() == InvestigationStatus.SCRAP_PENDING
                || investigation.getStatus() == InvestigationStatus.RELEASE_PENDING;
        if (liveConclusion
                && investigation.getDecision() == decision
                && encodedUnits.equals(investigation.getDecisionUnitCodes())
                && eq(investigation.getValidUntil(), validUntil)
                && eq(investigation.getUsageRestrictions(), usageRestrictions)) {
            // 确认幂等命中的结论审计记录存在
            conclusionRepository
                    .findByInvestigationIdOrderByCreatedAtAscIdAsc(investigation.getId())
                    .stream()
                    .filter(c -> c.getDecisionSeq() == investigation.getDecisionSeq())
                    .findFirst()
                    .orElseThrow();
            return investigation;
        }

        long seq = investigation.applyDecision(
                decision, encodedUnits, validUntil, usageRestrictions, Instant.now());
        ConclusionRecord record = new ConclusionRecord(
                investigation, seq, decision, encodedUnits,
                investigation.getEvidenceVersion(), decidedBy, validUntil, usageRestrictions);
        investigationRepository.saveAndFlush(investigation);
        conclusionRepository.saveAndFlush(record);
        return investigation;
    }

    /**
     * 有条件放行审批。审批号幂等：同号重放必须由相同审批人/角色提交，返回既有审批。
     * 一次放行结论需要两名不同角色，且两名审批人不能相同。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public ApprovalRecord approve(String investigationNo, String approvalNo,
                                  String approver, String role) {
        Investigation found = getInvestigation(investigationNo);
        Shipment shipment = lockShipment(found.getShipment());
        Investigation investigation = lockInvestigation(investigationNo);
        Optional<ApprovalRecord> replay = approvalRepository.findByApprovalNo(approvalNo);
        if (replay.isPresent()) {
            ApprovalRecord existing = replay.get();
            if (!existing.getInvestigation().getId().equals(investigation.getId())
                    || !existing.getApprover().equals(approver)
                    || !existing.getRole().equals(role)) {
                throw ApiException.conflict("APPROVAL_CONFLICT",
                        "审批号 " + approvalNo + " 已存在但审批人或角色不一致");
            }
            return existing;
        }
        if (investigation.getStatus() != InvestigationStatus.RELEASE_PENDING
                || investigation.getDecision() != InvestigationDecision.CONDITIONAL_RELEASE) {
            throw ApiException.conflict("APPROVAL_NOT_ALLOWED", "当前结论不是待放行的有条件放行结论");
        }
        if (evidenceExpanded(investigation, currentSnapshot(shipment))) {
            investigation.invalidate("后到历史温度事件扩大了异常区间，结论失效", Instant.now());
            investigation.refreshEvidence(currentSnapshot(shipment).signature());
            investigationRepository.saveAndFlush(investigation);
            throw ApiException.conflict("CONCLUSION_INVALIDATED",
                    "证据版本已扩大，结论已失效，请重新评估");
        }
        long seq = investigation.getDecisionSeq();
        List<ApprovalRecord> chain =
                approvalRepository.findByInvestigationIdAndDecisionSeqOrderByCreatedAtAscIdAsc(
                        investigation.getId(), seq);
        for (ApprovalRecord prior : chain) {
            if (prior.getRole().equals(role)) {
                throw ApiException.conflict("ROLE_ALREADY_APPROVED",
                        "角色 " + role + " 已审批过本次放行结论");
            }
            if (prior.getApprover().equals(approver)) {
                throw ApiException.conflict("APPROVER_ALREADY_APPROVED",
                        "两名审批人必须不同，" + approver + " 已审批过");
            }
        }
        ApprovalRecord record = new ApprovalRecord(
                approvalNo, investigation, approver, role, seq, investigation.getEvidenceVersion());
        try {
            return approvalRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("DUPLICATE_APPROVAL", "审批号已存在: " + approvalNo);
        }
    }

    /** 报废处置。处置号幂等，同号重放必须内容一致。 */
    @Transactional(noRollbackFor = ApiException.class)
    public DispositionRecord scrap(String investigationNo, String dispositionNo,
                                   String unitCode, String actedBy) {
        return dispose(investigationNo, dispositionNo, unitCode, actedBy, DispositionType.SCRAP);
    }

    /** 有条件放行处置：校验双角色审批、有效期与最新证据，处置号幂等。 */
    @Transactional(noRollbackFor = ApiException.class)
    public DispositionRecord release(String investigationNo, String dispositionNo,
                                     String unitCode, String actedBy) {
        return dispose(investigationNo, dispositionNo, unitCode, actedBy, DispositionType.RELEASE);
    }

    private DispositionRecord dispose(String investigationNo, String dispositionNo,
                                      String unitCode, String actedBy, DispositionType type) {
        Investigation found = getInvestigation(investigationNo);
        Shipment shipment = lockShipment(found.getShipment());
        Investigation investigation = lockInvestigation(investigationNo);
        Optional<DispositionRecord> replay = dispositionRepository.findByDispositionNo(dispositionNo);
        if (replay.isPresent()) {
            DispositionRecord existing = replay.get();
            if (!existing.getInvestigation().getId().equals(investigation.getId())
                    || existing.getType() != type
                    || !existing.getUnitCode().equals(unitCode)) {
                throw ApiException.conflict("DISPOSITION_CONFLICT",
                        "处置号 " + dispositionNo + " 已存在但内容不一致");
            }
            return existing;
        }

        PackagingUnit unit = unitRepository.findByShipmentIdAndUnitCode(shipment.getId(), unitCode)
                .orElseThrow(() -> ApiException.badRequest("UNIT_NOT_FOUND",
                        "单元不存在: " + unitCode));
        if (unit.getStatus() != UnitStatus.HELD) {
            throw ApiException.conflict("UNIT_ALREADY_DISPOSED",
                    "单元 " + unitCode + " 已处于" + unit.getStatus() + "，不能重复处置");
        }
        if (!isUnitCovered(investigation, unitCode)) {
            throw ApiException.conflict("UNIT_NOT_COVERED",
                    "当前结论未覆盖单元 " + unitCode);
        }
        InvestigationDecision required = type == DispositionType.SCRAP
                ? InvestigationDecision.SCRAP : InvestigationDecision.CONDITIONAL_RELEASE;
        if (investigation.getDecision() != required || isStale(investigation)) {
            throw ApiException.conflict("CONCLUSION_INVALIDATED",
                    "当前结论已失效或判定不匹配，请重新评估");
        }
        // 与隔离解除并发的新越界事件：重新计算证据，异常区间扩大则不得放行
        ExcursionCalculator.Snapshot snapshot = currentSnapshot(shipment);
        if (evidenceExpanded(investigation, snapshot)) {
            investigation.invalidate("处置时发现新的越界事件扩大了异常区间，结论失效", Instant.now());
            investigation.refreshEvidence(snapshot.signature());
            investigationRepository.saveAndFlush(investigation);
            throw ApiException.conflict(type == DispositionType.RELEASE
                            ? "RELEASE_REJECTED_NEW_EXCURSION" : "CONCLUSION_INVALIDATED",
                    "异常区间已扩大，仍然不满足处置条件，必须重新评估");
        }

        Instant now = Instant.now();
        Instant validUntil = investigation.getValidUntil();
        String restrictions = investigation.getUsageRestrictions();
        if (type == DispositionType.RELEASE) {
            List<ApprovalRecord> chain =
                    approvalRepository.findByInvestigationIdAndDecisionSeqOrderByCreatedAtAscIdAsc(
                            investigation.getId(), investigation.getDecisionSeq());
            if (chain.size() < 2) {
                throw ApiException.conflict("APPROVAL_INCOMPLETE",
                        "有条件放行需要两名不同角色的审批，当前仅 " + chain.size() + " 名");
            }
            if (validUntil == null || !validUntil.isAfter(now)) {
                throw ApiException.conflict("RELEASE_EXPIRED", "放行已超过有效期限，不得放行");
            }
            unit.release(now, validUntil, restrictions);
        } else {
            unit.scrap();
        }
        unitRepository.saveAndFlush(unit);
        DispositionRecord record = new DispositionRecord(
                dispositionNo, investigation, type, unitCode,
                investigation.getEvidenceVersion(), investigation.getDecisionSeq(),
                actedBy, type == DispositionType.RELEASE ? validUntil : null, restrictions);
        dispositionRepository.saveAndFlush(record);

        recalculateShipmentStatus(shipment);
        if (!hasHeldUnits(shipment)) {
            investigation.complete(now);
        }
        investigationRepository.saveAndFlush(investigation);
        return record;
    }

    @Transactional(readOnly = true)
    public InvestigationDetail getInvestigationDetail(String investigationNo) {
        Investigation investigation = getInvestigation(investigationNo);
        Shipment shipment = investigation.getShipment();
        List<TemperatureEvent> events =
                temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId());
        ExcursionCalculator.Snapshot liveSnapshot =
                ExcursionCalculator.analyze(events, shipment.getTempMin(), shipment.getTempMax(),
                        shipment.getMaxBreachSeconds());
        return new InvestigationDetail(
                investigation,
                ExcursionCalculator.parseSignature(investigation.getLockedEvidenceSignature()),
                liveSnapshot,
                conclusionRepository.findByInvestigationIdOrderByCreatedAtAscIdAsc(investigation.getId()),
                approvalRepository.findByInvestigationIdOrderByCreatedAtAscIdAsc(investigation.getId()),
                dispositionRepository.findByInvestigationIdOrderByCreatedAtAscIdAsc(investigation.getId()),
                unitRepository.findByShipmentIdOrderByIdAsc(shipment.getId()),
                events);
    }

    // ==================== 证据失效（由温度事件写入路径调用） ====================

    /**
     * 新温度事件落库后调用：若达阈异常区间集合相较任一未完成调查创建/刷新时的证据
     * 发生扩大，则使该调查未完成的结论失效并刷新证据快照，要求重新评估。
     */
    @Transactional
    public void invalidateIfEvidenceExpanded(Shipment shipment,
                                             ExcursionCalculator.Snapshot currentSnapshot) {
        if (currentSnapshot.isEmpty()) {
            return;
        }
        List<Investigation> active = investigationRepository
                .findByShipmentIdAndStatusInOrderByIdAsc(shipment.getId(), OPEN_STATUSES);
        Instant now = Instant.now();
        for (Investigation investigation : active) {
            if (investigation.getStatus() == InvestigationStatus.OPEN) {
                // OPEN 调查尚无结论，直接刷新证据快照，无需失效
                if (!investigation.getEvidenceSignature().equals(currentSnapshot.signature())) {
                    investigation.refreshEvidence(currentSnapshot.signature());
                    investigationRepository.save(investigation);
                }
                continue;
            }
            if (evidenceExpanded(investigation, currentSnapshot)) {
                investigation.invalidate("后到历史温度事件扩大了异常区间，结论失效，必须重新评估", now);
                investigation.refreshEvidence(currentSnapshot.signature());
                investigationRepository.save(investigation);
            }
        }
    }

    // ==================== 内部方法 ====================

    private ExcursionCalculator.Snapshot currentSnapshot(Shipment shipment) {
        List<TemperatureEvent> events =
                temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId());
        return ExcursionCalculator.analyze(events, shipment.getTempMin(), shipment.getTempMax(),
                shipment.getMaxBreachSeconds());
    }

    private void ensureUnits(Shipment shipment) {
        if (!unitRepository.findByShipmentIdOrderByIdAsc(shipment.getId()).isEmpty()) {
            return;
        }
        unitRepository.saveAndFlush(new PackagingUnit(shipment, DEFAULT_UNIT_CODE, 1));
    }

    /** 解析结论选择的单元："*"（或空）表示该运单全部单元。 */
    private String encodeUnitSelection(Investigation investigation, Shipment shipment,
                                       List<String> requested) {
        List<PackagingUnit> units = unitRepository.findByShipmentIdOrderByIdAsc(shipment.getId());
        List<String> codes = requested == null ? List.of() : requested.stream()
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .toList();
        if (codes.isEmpty() || codes.equals(List.of(ALL_UNITS))) {
            return ALL_UNITS;
        }
        List<String> known = units.stream().map(PackagingUnit::getUnitCode).toList();
        for (String code : codes) {
            if (!known.contains(code)) {
                throw ApiException.badRequest("UNIT_NOT_FOUND", "单元不存在: " + code);
            }
        }
        return String.join(",", codes);
    }

    private void requireAllUnitsHeld(Shipment shipment, String encodedUnits) {
        List<PackagingUnit> units = unitRepository.findByShipmentIdOrderByIdAsc(shipment.getId());
        for (PackagingUnit unit : units) {
            if (isCovered(encodedUnits, unit.getUnitCode()) && unit.getStatus() != UnitStatus.HELD) {
                throw ApiException.conflict("UNIT_ALREADY_DISPOSED",
                        "单元 " + unit.getUnitCode() + " 已处置，不能重复纳入结论");
            }
        }
    }

    private boolean isUnitCovered(Investigation investigation, String unitCode) {
        return isCovered(investigation.getDecisionUnitCodes(), unitCode);
    }

    private boolean isCovered(String encodedUnits, String unitCode) {
        if (encodedUnits == null) {
            return false;
        }
        if (ALL_UNITS.equals(encodedUnits)) {
            return true;
        }
        return Set.of(encodedUnits.split(",")).contains(unitCode);
    }

    private boolean hasHeldUnits(Shipment shipment) {
        return unitRepository.findByShipmentIdOrderByIdAsc(shipment.getId()).stream()
                .anyMatch(u -> u.getStatus() == UnitStatus.HELD);
    }

    private void recalculateShipmentStatus(Shipment shipment) {
        List<PackagingUnit> units = unitRepository.findByShipmentIdOrderByIdAsc(shipment.getId());
        boolean anyHeld = units.stream().anyMatch(u -> u.getStatus() == UnitStatus.HELD);
        boolean anyReleased = units.stream().anyMatch(u -> u.getStatus() == UnitStatus.RELEASED);
        boolean anyScrapped = units.stream().anyMatch(u -> u.getStatus() == UnitStatus.SCRAPPED);
        if (anyHeld) {
            shipment.setStatus(ShipmentStatus.QUARANTINED);
        } else if (anyReleased && anyScrapped) {
            shipment.setStatus(ShipmentStatus.RELEASED);
        } else if (anyReleased) {
            shipment.setStatus(ShipmentStatus.RELEASED);
        } else {
            shipment.setStatus(ShipmentStatus.CONDEMNED);
        }
        shipmentRepository.saveAndFlush(shipment);
    }

    private boolean isStale(Investigation investigation) {
        return investigation.getStatus() == InvestigationStatus.INVALIDATED
                || investigation.getStatus() == InvestigationStatus.COMPLETED
                || investigation.getStatus() == InvestigationStatus.OPEN;
    }

    /**
     * 异常区间扩大判定：区间集合数量变多，或任一同起点区间的终点后延
     * （含新增的触发事件），即视为扩大。区间缩小不影响既有结论。
     */
    private boolean evidenceExpanded(Investigation investigation,
                                     ExcursionCalculator.Snapshot current) {
        if (current.isEmpty()) {
            return false;
        }
        List<ExcursionCalculator.Interval> prior =
                ExcursionCalculator.parseSignature(investigation.getEvidenceSignature());
        if (current.intervals().size() > prior.size()) {
            return true;
        }
        for (int i = 0; i < prior.size(); i++) {
            ExcursionCalculator.Interval oldInterval = prior.get(i);
            ExcursionCalculator.Interval nowInterval = current.intervals().get(i);
            if (nowInterval.start().isBefore(oldInterval.start())
                    || nowInterval.end().isAfter(oldInterval.end())) {
                return true;
            }
        }
        return false;
    }

    private Shipment lockShipment(String waybillNo) {
        return shipmentRepository.findByWaybillNoForUpdate(waybillNo)
                .orElseThrow(() -> ApiException.notFound("运单不存在: " + waybillNo));
    }

    private Shipment getShipment(String waybillNo) {
        return shipmentRepository.findByWaybillNo(waybillNo)
                .orElseThrow(() -> ApiException.notFound("运单不存在: " + waybillNo));
    }

    private Shipment lockShipment(Shipment shipment) {
        return shipmentRepository.findByWaybillNoForUpdate(shipment.getWaybillNo())
                .orElseThrow(() -> ApiException.notFound("运单不存在: " + shipment.getWaybillNo()));
    }

    private Investigation lockInvestigation(String investigationNo) {
        return investigationRepository.findByInvestigationNoForUpdate(investigationNo)
                .orElseThrow(() -> ApiException.notFound("调查不存在: " + investigationNo));
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 调查详情聚合：异常区间、证据版本、审批链、各单元状态、完整温度时间线。 */
    public record InvestigationDetail(
            Investigation investigation,
            List<ExcursionCalculator.Interval> lockedIntervals,
            ExcursionCalculator.Snapshot liveEvidence,
            List<ConclusionRecord> conclusions,
            List<ApprovalRecord> approvals,
            List<DispositionRecord> dispositions,
            List<PackagingUnit> units,
            List<TemperatureEvent> temperatureTimeline) {
    }
}
