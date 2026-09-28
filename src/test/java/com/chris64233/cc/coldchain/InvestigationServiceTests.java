package com.chris64233.cc.coldchain;

import com.chris64233.cc.coldchain.domain.DispositionRecord;
import com.chris64233.cc.coldchain.domain.DispositionType;
import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationDecision;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
import com.chris64233.cc.coldchain.domain.PackagingUnit;
import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.domain.UnitStatus;
import com.chris64233.cc.coldchain.service.ApiException;
import com.chris64233.cc.coldchain.service.ColdChainService;
import com.chris64233.cc.coldchain.service.InvestigationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class InvestigationServiceTests {

    @Autowired
    private ColdChainService coldChain;

    @Autowired
    private InvestigationService investigations;

    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00Z");

    private void setupQuarantinedShipment(String waybill) {
        coldChain.createShipment(waybill, 2.0, 8.0, 600, "alice", T0);
        // 10:00 与 10:10 两次越界，区间长度 600 秒，达到隔离阈值
        coldChain.recordTemperatureEvent(waybill, "e1", T0.plus(2, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e2", T0.plus(2, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
        assertEquals(ShipmentStatus.QUARANTINED, coldChain.getShipment(waybill).getStatus());
    }

    private static void assertCode(String expected, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getCode());
    }

    // ---------- 调查创建与证据/阈值/保管方锁定 ----------

    @Test
    void createInvestigationLocksEvidenceThresholdAndCustodian() {
        String waybill = "WB-INV-1";
        setupQuarantinedShipment(waybill);

        coldChain.createShipment("WB-IN-TRANSIT-X", 2.0, 8.0, 600, "alice", T0);
        assertCode("SHIPMENT_NOT_QUARANTINED", () ->
                investigations.createInvestigation("WB-IN-TRANSIT-X", "INV-X"));
        Investigation inv = investigations.createInvestigation(waybill, "INV-001");
        assertEquals(InvestigationStatus.OPEN, inv.getStatus());
        assertEquals(1, inv.getEvidenceVersion());
        assertEquals("alice", inv.getCustodian());
        assertEquals(2.0, inv.getThresholdTempMin());
        assertEquals(8.0, inv.getThresholdTempMax());
        assertEquals(600, inv.getThresholdMaxBreachSeconds());
        assertEquals(List.of("e1", "e2"), List.of(inv.getTriggerEventIds().split(",")));
        assertEquals(T0.plus(2, ChronoUnit.HOURS), inv.getExcursionStart());
        assertEquals(T0.plus(2, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), inv.getExcursionEnd());

        // 未显式拆分时自动生成默认单元
        List<PackagingUnit> units = investigations.listUnits(waybill);
        assertEquals(1, units.size());
        assertEquals("DEFAULT", units.get(0).getUnitCode());
        assertEquals(UnitStatus.HELD, units.get(0).getStatus());

        // 调查号幂等：重放返回同一调查，不重复创建
        Investigation replay = investigations.createInvestigation(waybill, "INV-001");
        assertEquals(inv.getId(), replay.getId());
        assertEquals(1, investigations.listInvestigations(waybill).size());

        // 存在未完成调查时不能重复创建
        assertCode("INVESTIGATION_IN_PROGRESS", () ->
                investigations.createInvestigation(waybill, "INV-002"));

        assertCode("NOT_FOUND", () -> investigations.getInvestigation("NOPE"));
    }

    @Test
    void custodianSnapshotSurvivesLaterHolderChange() {
        String waybill = "WB-INV-2";
        setupQuarantinedShipment(waybill);
        Investigation inv = investigations.createInvestigation(waybill, "INV-002");

        investigations.submitConclusion(inv.getInvestigationNo(),
                InvestigationDecision.CONDITIONAL_RELEASE, null,
                Instant.now().plus(1, ChronoUnit.HOURS), "仅限工业用途", "qa-lead");
        investigations.approve(inv.getInvestigationNo(), "AP-2-1", "alice", "QA");
        investigations.approve(inv.getInvestigationNo(), "AP-2-2", "bob", "SAFETY");
        investigations.release(inv.getInvestigationNo(), "D-2-1", "DEFAULT", "warehouse");
        assertEquals(ShipmentStatus.RELEASED, coldChain.getShipment(waybill).getStatus());

        HandoverRecord handover = coldChain.initiateHandover(waybill, "alice", "carol");
        coldChain.confirmHandover(waybill, handover.getId(), "carol");
        assertEquals("carol", coldChain.getShipment(waybill).getCurrentHolder());

        // 调查锁定的保管方不随后续交接改变
        Investigation reloaded = investigations.getInvestigation("INV-002");
        assertEquals("alice", reloaded.getCustodian());
    }

    // ---------- 证据扩大导致结论失效、必须重新评估 ----------

    @Test
    void lateEventExtendingIntervalInvalidatesUnfinishedConclusion() {
        String waybill = "WB-INV-3";
        setupQuarantinedShipment(waybill);
        Investigation inv = investigations.createInvestigation(waybill, "INV-003");

        // 判定继续隔离
        investigations.submitConclusion("INV-003",
                InvestigationDecision.CONTINUE_QUARANTINE, null, null, null, "qa-lead");
        assertEquals(InvestigationStatus.CONTINUED, investigations.getInvestigation("INV-003").getStatus());

        // 区间内补点不改变起止，不失效
        coldChain.recordTemperatureEvent(waybill, "e1b",
                T0.plus(2, ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES), 11.0);
        assertEquals(InvestigationStatus.CONTINUED, investigations.getInvestigation("INV-003").getStatus());
        assertEquals(1, investigations.getInvestigation("INV-003").getEvidenceVersion());

        // 后到事件使区间终点后延（10:10 -> 10:20），结论失效
        coldChain.recordTemperatureEvent(waybill, "e3",
                T0.plus(2, ChronoUnit.HOURS).plus(20, ChronoUnit.MINUTES), 10.5);
        Investigation invalidated = investigations.getInvestigation("INV-003");
        assertEquals(InvestigationStatus.INVALIDATED, invalidated.getStatus());
        assertEquals(2, invalidated.getEvidenceVersion());
        assertNotNull(invalidated.getInvalidatedAt());
        assertTrue(invalidated.getInvalidationReason().contains("异常区间"));

        // 失效状态下提交放行判定，必须重新评估（产生新结论序号）
        Investigation renewed = investigations.submitConclusion("INV-003",
                InvestigationDecision.SCRAP, null, null, null, "qa-lead");
        assertEquals(2, renewed.getDecisionSeq());
        assertEquals(InvestigationStatus.SCRAP_PENDING, renewed.getStatus());

        investigations.scrap("INV-003", "D-SCRAP-1", "DEFAULT", "warehouse");
        Investigation completed = investigations.getInvestigation("INV-003");
        assertEquals(InvestigationStatus.COMPLETED, completed.getStatus());
        assertEquals(ShipmentStatus.CONDEMNED, coldChain.getShipment(waybill).getStatus());

        // 完成后再到的事件不再改变终态调查
        coldChain.recordTemperatureEvent(waybill, "e4",
                T0.plus(3, ChronoUnit.HOURS), 12.0);
        completed = investigations.getInvestigation("INV-003");
        assertEquals(InvestigationStatus.COMPLETED, completed.getStatus());
        assertEquals(2, completed.getEvidenceVersion());
    }

    @Test
    void openInvestigationRefreshesEvidenceWithoutInvalidationAndNewIntervalAlsoExpands() {
        String waybill = "WB-INV-4";
        coldChain.createShipment(waybill, 2.0, 8.0, 600, "alice", T0);
        // 闭合区间：10:00、10:10 越界，10:20 回温，区间 10:00..10:20
        coldChain.recordTemperatureEvent(waybill, "e1", T0.plus(2, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e2", T0.plus(2, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e3", T0.plus(2, ChronoUnit.HOURS).plus(20, ChronoUnit.MINUTES), 5.0);

        Investigation inv = investigations.createInvestigation(waybill, "INV-004");
        assertEquals(1, inv.getEvidenceVersion());

        // OPEN 状态下证据扩大只刷新版本，不产生失效结论
        coldChain.recordTemperatureEvent(waybill, "e4", T0.plus(3, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e5", T0.plus(3, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
        Investigation refreshed = investigations.getInvestigation("INV-004");
        assertEquals(InvestigationStatus.OPEN, refreshed.getStatus());
        assertEquals(2, refreshed.getEvidenceVersion());

        // 有了结论后再出现新的独立越界区间，结论失效
        investigations.submitConclusion("INV-004",
                InvestigationDecision.CONTINUE_QUARANTINE, null, null, null, "qa-lead");
        coldChain.recordTemperatureEvent(waybill, "e6", T0.plus(4, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e7", T0.plus(4, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
        assertEquals(InvestigationStatus.INVALIDATED, investigations.getInvestigation("INV-004").getStatus());
        assertEquals(3, investigations.getInvestigation("INV-004").getEvidenceVersion());
    }

    // ---------- 双角色审批与受控放行 ----------

    @Test
    void conditionalReleaseRequiresTwoDistinctRolesAndPeople() {
        String waybill = "WB-INV-5";
        coldChain.createShipment(waybill, 2.0, 8.0, 600, "alice", T0);
        investigations.registerUnit(waybill, "U1", 10);
        investigations.registerUnit(waybill, "U2", 20);
        setupQuarantineEvents(waybill);

        Investigation inv = investigations.createInvestigation(waybill, "INV-005");

        // 有条件放行必须带有效期与使用限制
        assertCode("VALID_UNTIL_REQUIRED", () -> investigations.submitConclusion("INV-005",
                InvestigationDecision.CONDITIONAL_RELEASE, List.of("U1"), null, "限制", "qa"));
        assertCode("USAGE_RESTRICTIONS_REQUIRED", () -> investigations.submitConclusion("INV-005",
                InvestigationDecision.CONDITIONAL_RELEASE, List.of("U1"),
                Instant.now().plus(1, ChronoUnit.HOURS), "  ", "qa"));
        assertCode("INVALID_VALID_UNTIL", () -> investigations.submitConclusion("INV-005",
                InvestigationDecision.CONDITIONAL_RELEASE, List.of("U1"),
                Instant.now().minus(1, ChronoUnit.MINUTES), "限制", "qa"));
        // 未知单元
        assertCode("UNIT_NOT_FOUND", () -> investigations.submitConclusion("INV-005",
                InvestigationDecision.CONDITIONAL_RELEASE, List.of("NOPE"),
                Instant.now().plus(1, ChronoUnit.HOURS), "限制", "qa"));

        investigations.submitConclusion("INV-005",
                InvestigationDecision.CONDITIONAL_RELEASE, List.of("U1"),
                Instant.now().plus(1, ChronoUnit.HOURS), "仅限工业用途，72h 内使用", "qa-lead");

        // 审批不足不能放行
        assertCode("APPROVAL_INCOMPLETE", () ->
                investigations.release("INV-005", "D-REL-1", "U1", "warehouse"));

        // 同角色不能重复审批
        investigations.approve("INV-005", "AP-1", "alice", "QA");
        assertCode("ROLE_ALREADY_APPROVED", () ->
                investigations.approve("INV-005", "AP-2", "carol", "QA"));
        // 同一人不能充当两个角色
        assertCode("APPROVER_ALREADY_APPROVED", () ->
                investigations.approve("INV-005", "AP-3", "alice", "SAFETY"));

        // 审批号幂等：同号同人同角色返回原记录；内容冲突拒绝
        ApprovalCheck.approveReturnsSame(investigations, "AP-1", "alice", "QA");
        assertCode("APPROVAL_CONFLICT", () ->
                investigations.approve("INV-005", "AP-1", "alice", "SAFETY"));

        investigations.approve("INV-005", "AP-4", "bob", "SAFETY");
        DispositionRecord release = investigations.release("INV-005", "D-REL-1", "U1", "warehouse");
        assertEquals(DispositionType.RELEASE, release.getType());
        assertEquals("仅限工业用途，72h 内使用", release.getUsageRestrictions());

        Map<String, PackagingUnit> units = unitMap(waybill);
        assertEquals(UnitStatus.RELEASED, units.get("U1").getStatus());
        assertEquals("仅限工业用途，72h 内使用", units.get("U1").getUsageRestrictions());
        assertNotNull(units.get("U1").getValidUntil());
        assertEquals(UnitStatus.HELD, units.get("U2").getStatus());
        // 还有 HELD 单元，运单保持隔离
        assertEquals(ShipmentStatus.QUARANTINED, coldChain.getShipment(waybill).getStatus());
        // 调查仍未完成（U2 未处置）
        assertEquals(InvestigationStatus.RELEASE_PENDING, investigations.getInvestigation("INV-005").getStatus());

        // 数量守恒/状态互斥：已放行单元不能再放行或报废
        assertCode("UNIT_ALREADY_DISPOSED", () ->
                investigations.release("INV-005", "D-REL-2", "U1", "warehouse"));
        assertCode("UNIT_ALREADY_DISPOSED", () ->
                investigations.scrap("INV-005", "D-SCRAP-X", "U1", "warehouse"));
        // 结论 "*" 不能覆盖已处置单元
        assertCode("UNIT_ALREADY_DISPOSED", () -> investigations.submitConclusion("INV-005",
                InvestigationDecision.CONDITIONAL_RELEASE, null,
                Instant.now().plus(1, ChronoUnit.HOURS), "限制", "qa"));
    }

    @Test
    void nonReleaseConclusionRejectsReleaseConditions() {
        String waybill = "WB-INV-6";
        setupQuarantinedShipment(waybill);
        investigations.createInvestigation(waybill, "INV-006");
        // SCRAP 结论即使带了有效期/限制也被清空；此时审批不允许
        investigations.submitConclusion("INV-006",
                InvestigationDecision.SCRAP, null,
                Instant.now().plus(1, ChronoUnit.HOURS), "限制", "qa");
        assertCode("APPROVAL_NOT_ALLOWED", () ->
                investigations.approve("INV-006", "AP-9", "alice", "QA"));
        // 判定与处置类型不匹配
        assertCode("CONCLUSION_INVALIDATED", () ->
                investigations.release("INV-006", "D-9", "DEFAULT", "w"));
    }

    // ---------- 报废 + 放行混合的数量守恒 ----------

    @Test
    void mixedScrapAndReleaseConservesQuantityAndCompletesInvestigation() {
        String waybill = "WB-INV-7";
        coldChain.createShipment(waybill, 2.0, 8.0, 600, "alice", T0);
        investigations.registerUnit(waybill, "U1", 10);
        investigations.registerUnit(waybill, "U2", 20);
        setupQuarantineEvents(waybill);
        investigations.createInvestigation(waybill, "INV-007");

        // 先放行 U1
        investigations.submitConclusion("INV-007",
                InvestigationDecision.CONDITIONAL_RELEASE, List.of("U1"),
                Instant.now().plus(1, ChronoUnit.HOURS), "限用", "qa");
        investigations.approve("INV-007", "A7-1", "alice", "QA");
        investigations.approve("INV-007", "A7-2", "bob", "SAFETY");
        investigations.release("INV-007", "D7-1", "U1", "w");

        // 对剩余 U2 判报废（必须显式指定，不能用 *）
        investigations.submitConclusion("INV-007",
                InvestigationDecision.SCRAP, List.of("U2"), null, null, "qa");
        DispositionRecord scrap = investigations.scrap("INV-007", "D7-2", "U2", "w");
        assertEquals(DispositionType.SCRAP, scrap.getType());

        Map<String, PackagingUnit> units = unitMap(waybill);
        assertEquals(UnitStatus.RELEASED, units.get("U1").getStatus());
        assertEquals(UnitStatus.SCRAPPED, units.get("U2").getStatus());
        // 数量守恒：放行 10 + 报废 20 = 初始 30，且单元状态互斥
        int total = units.values().stream().mapToInt(PackagingUnit::getQuantity).sum();
        int accounted = units.values().stream()
                .filter(u -> u.getStatus() != UnitStatus.HELD)
                .mapToInt(PackagingUnit::getQuantity).sum();
        assertEquals(30, total);
        assertEquals(30, accounted);

        assertEquals(InvestigationStatus.COMPLETED, investigations.getInvestigation("INV-007").getStatus());
        assertEquals(ShipmentStatus.RELEASED, coldChain.getShipment(waybill).getStatus());

        // 处置号幂等：一致重放返回同一记录；冲突拒绝
        DispositionRecord replay = investigations.scrap("INV-007", "D7-2", "U2", "w");
        assertEquals(scrap.getId(), replay.getId());
        assertCode("DISPOSITION_CONFLICT", () ->
                investigations.scrap("INV-007", "D7-2", "U1", "w"));
        assertEquals(2, investigations.getInvestigationDetail("INV-007").dispositions().size());
    }

    // ---------- 新越界事件并发到达时不得放行 ----------

    @Test
    void releaseBlockedAfterNewExcursionExpandsEvidence() {
        String waybill = "WB-INV-8";
        coldChain.createShipment(waybill, 2.0, 8.0, 600, "alice", T0);
        coldChain.recordTemperatureEvent(waybill, "e1", T0.plus(2, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e2", T0.plus(2, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e3", T0.plus(2, ChronoUnit.HOURS).plus(20, ChronoUnit.MINUTES), 5.0);
        investigations.createInvestigation(waybill, "INV-008");
        investigations.submitConclusion("INV-008",
                InvestigationDecision.CONDITIONAL_RELEASE, null,
                Instant.now().plus(1, ChronoUnit.HOURS), "限用", "qa");
        investigations.approve("INV-008", "A8-1", "alice", "QA");
        investigations.approve("INV-008", "A8-2", "bob", "SAFETY");

        // 放行前出现新的独立越界区间
        coldChain.recordTemperatureEvent(waybill, "e4", T0.plus(4, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e5", T0.plus(4, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);

        Investigation invalidated = investigations.getInvestigation("INV-008");
        assertEquals(InvestigationStatus.INVALIDATED, invalidated.getStatus());
        assertCode("CONCLUSION_INVALIDATED", () ->
                investigations.release("INV-008", "D8-0", "DEFAULT", "w"));
        assertEquals(UnitStatus.HELD, investigations.listUnits(waybill).get(0).getStatus());
        assertEquals(ShipmentStatus.QUARANTINED, coldChain.getShipment(waybill).getStatus());

        // 重新评估、重新双审批后才能放行
        investigations.submitConclusion("INV-008",
                InvestigationDecision.CONDITIONAL_RELEASE, null,
                Instant.now().plus(1, ChronoUnit.HOURS), "限用", "qa");
        // 旧审批绑定旧结论序号，不再满足放行
        assertCode("APPROVAL_INCOMPLETE", () ->
                investigations.release("INV-008", "D8-1", "DEFAULT", "w"));
        investigations.approve("INV-008", "A8-3", "alice", "QA");
        investigations.approve("INV-008", "A8-4", "bob", "SAFETY");
        investigations.release("INV-008", "D8-1", "DEFAULT", "w");
        assertEquals(UnitStatus.RELEASED, investigations.listUnits(waybill).get(0).getStatus());
        assertEquals(ShipmentStatus.RELEASED, coldChain.getShipment(waybill).getStatus());
    }

    @Test
    void releaseAfterExpiryIsRejected() throws InterruptedException {
        String waybill = "WB-INV-9";
        setupQuarantinedShipment(waybill);
        investigations.createInvestigation(waybill, "INV-009");
        investigations.submitConclusion("INV-009",
                InvestigationDecision.CONDITIONAL_RELEASE, null,
                Instant.now().plus(300, ChronoUnit.MILLIS), "限用", "qa");
        investigations.approve("INV-009", "A9-1", "alice", "QA");
        investigations.approve("INV-009", "A9-2", "bob", "SAFETY");
        Thread.sleep(700);
        assertCode("RELEASE_EXPIRED", () ->
                investigations.release("INV-009", "D9-1", "DEFAULT", "w"));
        assertEquals(UnitStatus.HELD, investigations.listUnits(waybill).get(0).getStatus());
    }

    // ---------- 结论幂等 ----------

    @Test
    void conclusionReplayIsIdempotentButChangeCreatesNewSeq() {
        String waybill = "WB-INV-10";
        setupQuarantinedShipment(waybill);
        investigations.createInvestigation(waybill, "INV-010");

        Investigation first = investigations.submitConclusion("INV-010",
                InvestigationDecision.CONTINUE_QUARANTINE, null, null, null, "qa");
        assertEquals(1, first.getDecisionSeq());
        // 完全相同的重放不产生新结论序号
        Investigation replay = investigations.submitConclusion("INV-010",
                InvestigationDecision.CONTINUE_QUARANTINE, List.of("*"), null, null, "qa");
        assertEquals(1, replay.getDecisionSeq());
        // 改变判定 -> 新序号
        Investigation changed = investigations.submitConclusion("INV-010",
                InvestigationDecision.SCRAP, null, null, null, "qa");
        assertEquals(2, changed.getDecisionSeq());
        assertEquals(InvestigationStatus.SCRAP_PENDING, changed.getStatus());
    }

    // ---------- 详情聚合 ----------

    @Test
    void detailIncludesIntervalsEvidenceApprovalsUnitsAndTimeline() {
        String waybill = "WB-INV-11";
        coldChain.createShipment(waybill, 2.0, 8.0, 600, "alice", T0);
        investigations.registerUnit(waybill, "U1", 5);
        investigations.registerUnit(waybill, "U2", 7);
        setupQuarantineEvents(waybill);
        investigations.createInvestigation(waybill, "INV-011");
        investigations.submitConclusion("INV-011",
                InvestigationDecision.CONDITIONAL_RELEASE, List.of("U1"),
                Instant.now().plus(1, ChronoUnit.HOURS), "限用", "qa");
        investigations.approve("INV-011", "A11-1", "alice", "QA");

        var detail = investigations.getInvestigationDetail("INV-011");
        assertEquals(1, detail.lockedIntervals().size());
        assertFalse(detail.liveEvidence().isEmpty());
        assertEquals(1, detail.conclusions().size());
        assertEquals(1, detail.approvals().size());
        assertEquals("QA", detail.approvals().get(0).getRole());
        assertEquals(2, detail.units().size());
        assertEquals(2, detail.temperatureTimeline().size());
        assertEquals("e1", detail.temperatureTimeline().get(0).getEventId());
    }

    @Test
    void cannotRegisterUnitsWhileInvestigationOpen() {
        String waybill = "WB-INV-12";
        setupQuarantinedShipment(waybill);
        investigations.createInvestigation(waybill, "INV-012");
        assertCode("INVESTIGATION_IN_PROGRESS", () ->
                investigations.registerUnit(waybill, "LATE", 1));
        coldChain.createShipment("WB-INV-12-DUP", 2.0, 8.0, 600, "alice", T0);
        assertCode("INVALID_QUANTITY", () ->
                investigations.registerUnit("WB-INV-12-DUP", "U1", 0));
        investigations.registerUnit("WB-INV-12-DUP", "U1", 3);
        assertCode("DUPLICATE_UNIT", () ->
                investigations.registerUnit("WB-INV-12-DUP", "U1", 3));
    }

    private void setupQuarantineEvents(String waybill) {
        coldChain.recordTemperatureEvent(waybill, "e1", T0.plus(2, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e2", T0.plus(2, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
        assertEquals(ShipmentStatus.QUARANTINED, coldChain.getShipment(waybill).getStatus());
    }

    private Map<String, PackagingUnit> unitMap(String waybill) {
        return investigations.listUnits(waybill).stream()
                .collect(Collectors.toMap(PackagingUnit::getUnitCode, u -> u));
    }

    /** 小辅助：避免在 lambda 中写带返回值的方法引用。 */
    private static final class ApprovalCheck {
        static void approveReturnsSame(InvestigationService service, String no, String who, String role) {
            var a = service.approve("INV-005", no, who, role);
            assertEquals(no, a.getApprovalNo());
        }
    }
}
