package com.chris64233.cc.coldchain;

import com.chris64233.cc.coldchain.domain.DispositionType;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
import com.chris64233.cc.coldchain.domain.PackingUnit;
import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.domain.UnitStatus;
import com.chris64233.cc.coldchain.service.ApiException;
import com.chris64233.cc.coldchain.service.ColdChainService;
import com.chris64233.cc.coldchain.service.InvestigationService;
import com.chris64233.cc.coldchain.service.InvestigationView;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class InvestigationWorkflowTests {

    @Autowired
    private ColdChainService coldChainService;

    @Autowired
    private InvestigationService investigationService;

    private void setupQuarantinedShipment(String waybill, String... unitCodes) {
        coldChainService.createShipment(waybill, 2.0, 8.0, 600, "alice",
                Instant.parse("2026-01-01T08:00:00Z"));
        for (String code : unitCodes) {
            coldChainService.registerPackingUnit(waybill, code);
        }
        coldChainService.recordTemperatureEvent(waybill, "e1",
                Instant.parse("2026-01-01T10:00:00Z"), 10.0);
        coldChainService.recordTemperatureEvent(waybill, "e2",
                Instant.parse("2026-01-01T10:10:00Z"), 10.0);
        assertEquals(ShipmentStatus.QUARANTINED, coldChainService.getShipment(waybill).getStatus());
    }

    private void assertErrorCode(String code, Runnable runnable) {
        ApiException ex = assertThrows(ApiException.class, runnable::run);
        assertEquals(code, ex.getCode());
    }

    @Test
    void investigationLocksEvidenceThresholdAndHolderAtCreation() {
        setupQuarantinedShipment("WB-INV-1", "u1", "u2");
        Investigation investigation = investigationService.createInvestigation(
                "WB-INV-1", "INV-1", null, "温度异常调查");

        assertEquals(InvestigationStatus.OPEN, investigation.getStatus());
        assertEquals(1, investigation.getEvidenceVersion());
        assertEquals("alice", investigation.getLockedHolder());
        assertEquals(2.0, investigation.getLockedTempMin());
        assertEquals(8.0, investigation.getLockedTempMax());
        assertEquals(600, investigation.getLockedMaxBreachSeconds());
        // 触发隔离的两个越界样本被锁定为证据
        assertEquals(2, investigation.getEvidenceEvents().size());
        assertEquals(List.of("u1", "u2"),
                investigation.getUnits().stream().map(link -> link.getPackingUnit().getUnitCode()).sorted().toList());
    }

    @Test
    void investigationCanOnlyBeCreatedWhenQuarantined() {
        coldChainService.createShipment("WB-INV-2", 2.0, 8.0, 600, "alice",
                Instant.parse("2026-01-01T08:00:00Z"));
        coldChainService.registerPackingUnit("WB-INV-2", "u1");
        assertErrorCode("SHIPMENT_NOT_QUARANTINED", () ->
                investigationService.createInvestigation("WB-INV-2", "INV-2", null, "未隔离"));
    }

    @Test
    void duplicateInvestigationNoIsIdempotentOnlyWhenCoverageMatches() {
        setupQuarantinedShipment("WB-INV-3", "u1", "u2");
        Investigation first = investigationService.createInvestigation(
                "WB-INV-3", "INV-3", null, "全部单元");
        Investigation replay = investigationService.createInvestigation(
                "WB-INV-3", "INV-3", null, "重放");
        assertEquals(first.getId(), replay.getId());

        assertErrorCode("INVESTIGATION_NO_CONFLICT", () ->
                investigationService.createInvestigation("WB-INV-3", "INV-3", List.of("u1"), "范围不同"));
    }

    @Test
    void specifiedUnitsOnlyAffectSelectedUnits() {
        setupQuarantinedShipment("WB-INV-4", "u1", "u2", "u3");
        investigationService.createInvestigation("WB-INV-4", "INV-4", List.of("u1"), "仅调查 u1");
        investigationService.conclude("WB-INV-4", "INV-4", "d1",
                DispositionType.DESTROY, null, null, "qa");

        Map<String, UnitStatus> statuses = unitStatusMap("WB-INV-4");
        assertEquals(UnitStatus.DESTROYED, statuses.get("u1"));
        assertEquals(UnitStatus.QUARANTINED, statuses.get("u2"));
        assertEquals(UnitStatus.QUARANTINED, statuses.get("u3"));
    }

    @Test
    void continueQuarantineKeepsUnitsQuarantined() {
        setupQuarantinedShipment("WB-INV-5", "u1");
        investigationService.createInvestigation("WB-INV-5", "INV-5", null, null);
        investigationService.conclude("WB-INV-5", "INV-5", "d1",
                DispositionType.CONTINUE_QUARANTINE, null, null, "qa");

        Investigation investigation = investigationService.listInvestigations("WB-INV-5").get(0);
        assertEquals(InvestigationStatus.COMPLETED, investigation.getStatus());
        assertEquals(UnitStatus.QUARANTINED, unitStatusMap("WB-INV-5").get("u1"));
    }

    @Test
    void cannotCreateSecondOpenInvestigationForSameUnit() {
        setupQuarantinedShipment("WB-INV-6", "u1", "u2");
        investigationService.createInvestigation("WB-INV-6", "INV-6A", List.of("u1"), null);
        assertErrorCode("INVESTIGATION_PENDING_UNIT", () ->
                investigationService.createInvestigation("WB-INV-6", "INV-6B", List.of("u1", "u2"), null));
        // 未被覆盖的 u2 仍可单独调查
        Investigation other = investigationService.createInvestigation(
                "WB-INV-6", "INV-6C", List.of("u2"), null);
        assertEquals(List.of("u2"),
                other.getUnits().stream().map(link -> link.getPackingUnit().getUnitCode()).toList());
    }

    @Test
    void conditionalReleaseRequiresTwoDifferentRolesAndExpiryAndRestrictions() {
        setupQuarantinedShipment("WB-INV-7", "u1");
        investigationService.createInvestigation("WB-INV-7", "INV-7", null, null);

        Instant validUntil = Instant.now().plus(1, ChronoUnit.DAYS);
        // 缺少有效期
        assertErrorCode("INVALID_VALID_UNTIL", () ->
                investigationService.conclude("WB-INV-7", "INV-7", "d-bad",
                        DispositionType.CONDITIONAL_RELEASE, Instant.now().minusSeconds(60),
                        "仅工业用途", "qa"));
        // 缺少使用限制
        assertErrorCode("MISSING_USAGE_RESTRICTIONS", () ->
                investigationService.conclude("WB-INV-7", "INV-7", "d-bad2",
                        DispositionType.CONDITIONAL_RELEASE, validUntil, "  ", "qa"));

        investigationService.conclude("WB-INV-7", "INV-7", "d1",
                DispositionType.CONDITIONAL_RELEASE, validUntil, "仅限工业用途，不得零售", "qa");

        // 未完成两角色审批前不得放行
        assertErrorCode("APPROVAL_INCOMPLETE", () ->
                investigationService.executeRelease("WB-INV-7", "INV-7", "rel1", "ops"));

        investigationService.approve("WB-INV-7", "INV-7", "a1", "bob", "QA_MANAGER");
        // 同一人不能重复审批
        assertErrorCode("APPROVAL_DUPLICATE_APPROVER", () ->
                investigationService.approve("WB-INV-7", "INV-7", "a2", "bob", "REGULATORY"));
        // 同角色不能重复审批
        assertErrorCode("APPROVAL_DUPLICATE_ROLE", () ->
                investigationService.approve("WB-INV-7", "INV-7", "a2", "carol", "QA_MANAGER"));

        investigationService.approve("WB-INV-7", "INV-7", "a2", "carol", "REGULATORY");
        Investigation released = investigationService.executeRelease("WB-INV-7", "INV-7", "rel1", "ops");

        assertEquals(InvestigationStatus.COMPLETED, released.getStatus());
        assertEquals(UnitStatus.RELEASED, unitStatusMap("WB-INV-7").get("u1"));
    }

    @Test
    void approvalAndDispositionEventsAreIdempotent() {
        setupQuarantinedShipment("WB-INV-8", "u1");
        investigationService.createInvestigation("WB-INV-8", "INV-8", null, null);
        Instant validUntil = Instant.now().plus(1, ChronoUnit.DAYS);
        investigationService.conclude("WB-INV-8", "INV-8", "d1",
                DispositionType.CONDITIONAL_RELEASE, validUntil, "仅限工业用途", "qa");

        var firstApproval = investigationService.approve("WB-INV-8", "INV-8", "a1", "bob", "QA_MANAGER");
        var replayApproval = investigationService.approve("WB-INV-8", "INV-8", "a1", "bob", "QA_MANAGER");
        assertEquals(firstApproval.getId(), replayApproval.getId());

        investigationService.approve("WB-INV-8", "INV-8", "a2", "carol", "REGULATORY");
        var firstRelease = investigationService.executeRelease("WB-INV-8", "INV-8", "rel1", "ops");
        var replayRelease = investigationService.executeRelease("WB-INV-8", "INV-8", "rel1", "ops");
        assertEquals(firstRelease.getId(), replayRelease.getId());

        // 相同事件号但内容不一致 -> 冲突
        assertErrorCode("APPROVAL_CONFLICT", () ->
                investigationService.approve("WB-INV-8", "INV-8", "a1", "mallory", "QA_MANAGER"));
        assertErrorCode("DISPOSITION_CONFLICT", () ->
                investigationService.executeRelease("WB-INV-8", "INV-8", "d1", "ops"));

        InvestigationView view = investigationService.getInvestigationView("WB-INV-8", "INV-8");
        assertEquals(2, view.approvals().size());
        assertEquals(2, view.dispositionEvents().size());
        assertEquals(UnitStatus.RELEASED, unitStatusMap("WB-INV-8").get("u1"));
    }

    @Test
    void quantityIsConservedWhenUnitsAreSplitAcrossDispositions() {
        setupQuarantinedShipment("WB-INV-9", "u1", "u2");
        // u1 报废
        investigationService.createInvestigation("WB-INV-9", "INV-9A", List.of("u1"), null);
        investigationService.conclude("WB-INV-9", "INV-9A", "d1",
                DispositionType.DESTROY, null, null, "qa");
        // u2 有条件放行
        investigationService.createInvestigation("WB-INV-9", "INV-9B", List.of("u2"), null);
        Instant validUntil = Instant.now().plus(1, ChronoUnit.DAYS);
        investigationService.conclude("WB-INV-9", "INV-9B", "d2",
                DispositionType.CONDITIONAL_RELEASE, validUntil, "限工业用途", "qa");
        investigationService.approve("WB-INV-9", "INV-9B", "a1", "bob", "QA_MANAGER");
        investigationService.approve("WB-INV-9", "INV-9B", "a2", "carol", "REGULATORY");
        investigationService.executeRelease("WB-INV-9", "INV-9B", "rel2", "ops");

        Map<String, UnitStatus> statuses = unitStatusMap("WB-INV-9");
        assertEquals(UnitStatus.DESTROYED, statuses.get("u1"));
        assertEquals(UnitStatus.RELEASED, statuses.get("u2"));

        // 已报废的单元不能再纳入新调查处置
        assertErrorCode("UNIT_NOT_QUARANTINED", () ->
                investigationService.createInvestigation("WB-INV-9", "INV-9C", List.of("u1"), null));
    }

    @Test
    void lateHistoricalEventThatExpandsBreachInvalidatesPendingConclusion() {
        setupQuarantinedShipment("WB-INV-10", "u1", "u2");
        Investigation investigation = investigationService.createInvestigation(
                "WB-INV-10", "INV-10", null, null);
        assertEquals(1, investigation.getEvidenceVersion());

        // 已判定有条件放行、双角色已审批，尚未执行放行
        Instant validUntil = Instant.now().plus(1, ChronoUnit.DAYS);
        investigationService.conclude("WB-INV-10", "INV-10", "d1",
                DispositionType.CONDITIONAL_RELEASE, validUntil, "限工业用途", "qa");
        investigationService.approve("WB-INV-10", "INV-10", "a1", "bob", "QA_MANAGER");
        investigationService.approve("WB-INV-10", "INV-10", "a2", "carol", "REGULATORY");

        // 晚到的更早越界历史样本，与原区间合并后扩大了异常区间
        coldChainService.recordTemperatureEvent("WB-INV-10", "e0",
                Instant.parse("2026-01-01T09:55:00Z"), 10.0);

        Shipment shipment = coldChainService.getShipment("WB-INV-10");
        assertEquals(2, shipment.getEvidenceVersion());

        Investigation superseded = investigationService.listInvestigations("WB-INV-10").get(0);
        assertEquals(InvestigationStatus.SUPERSEDED, superseded.getStatus());
        assertTrue(superseded.getSupersedeReason().contains("证据版本"));

        // 放行被拒绝
        assertErrorCode("NEW_BREACH_AFTER_APPROVAL", () ->
                investigationService.executeRelease("WB-INV-10", "INV-10", "rel1", "ops"));
        // 审批也被拒绝
        assertErrorCode("INVESTIGATION_SUPERSEDED", () ->
                investigationService.approve("WB-INV-10", "INV-10", "a3", "dave", "SAFETY"));

        // 基于新证据重新调查后可以继续处置
        Investigation reopened = investigationService.createInvestigation(
                "WB-INV-10", "INV-10-R2", null, "重新评估");
        assertEquals(2, reopened.getEvidenceVersion());
        assertEquals(3, reopened.getEvidenceEvents().size());
        investigationService.conclude("WB-INV-10", "INV-10-R2", "d2",
                DispositionType.CONTINUE_QUARANTINE, null, null, "qa");
    }

    @Test
    void lateEventInsideExistingIntervalDoesNotInvalidateConclusion() {
        setupQuarantinedShipment("WB-INV-11", "u1");
        // 补一个落在原越界区间内的样本：区间不变，结论仍然有效
        coldChainService.recordTemperatureEvent("WB-INV-11", "e-mid",
                Instant.parse("2026-01-01T10:05:00Z"), 9.5);
        Investigation investigation = investigationService.createInvestigation(
                "WB-INV-11", "INV-11", null, null);
        assertEquals(1, investigation.getEvidenceVersion());

        coldChainService.recordTemperatureEvent("WB-INV-11", "e-mid2",
                Instant.parse("2026-01-01T10:06:00Z"), 9.6);
        assertEquals(1, coldChainService.getShipment("WB-INV-11").getEvidenceVersion());

        investigationService.conclude("WB-INV-11", "INV-11", "d1",
                DispositionType.DESTROY, null, null, "qa");
        assertEquals(InvestigationStatus.COMPLETED,
                investigationService.listInvestigations("WB-INV-11").get(0).getStatus());
    }

    @Test
    void newSeparateBreachIntervalAlsoInvalidates() {
        // 构造：先因第一段越界隔离，之后补到的历史中又出现第二段独立越界
        coldChainService.createShipment("WB-INV-12", 2.0, 8.0, 600, "alice",
                Instant.parse("2026-01-01T08:00:00Z"));
        coldChainService.registerPackingUnit("WB-INV-12", "u1");
        coldChainService.recordTemperatureEvent("WB-INV-12", "e1",
                Instant.parse("2026-01-01T10:00:00Z"), 10.0);
        coldChainService.recordTemperatureEvent("WB-INV-12", "e2",
                Instant.parse("2026-01-01T10:10:00Z"), 10.0);
        coldChainService.recordTemperatureEvent("WB-INV-12", "e3",
                Instant.parse("2026-01-01T11:00:00Z"), 5.0);
        investigationService.createInvestigation("WB-INV-12", "INV-12", null, null);

        // 晚到的第二段越界（12:00-12:10），与第一段独立且达到阈值
        coldChainService.recordTemperatureEvent("WB-INV-12", "e4",
                Instant.parse("2026-01-01T12:00:00Z"), 10.0);
        coldChainService.recordTemperatureEvent("WB-INV-12", "e5",
                Instant.parse("2026-01-01T12:10:00Z"), 10.0);

        assertEquals(2, coldChainService.getShipment("WB-INV-12").getEvidenceVersion());
        assertEquals(InvestigationStatus.SUPERSEDED,
                investigationService.listInvestigations("WB-INV-12").get(0).getStatus());
    }

    @Test
    void investigationViewContainsIntervalsEvidenceApprovalsUnitsAndTimeline() {
        setupQuarantinedShipment("WB-INV-13", "u1");
        investigationService.createInvestigation("WB-INV-13", "INV-13", List.of("u1"), "异常");
        Instant validUntil = Instant.now().plus(2, ChronoUnit.DAYS);
        investigationService.conclude("WB-INV-13", "INV-13", "d1",
                DispositionType.CONDITIONAL_RELEASE, validUntil, "限工业用途", "qa");
        investigationService.approve("WB-INV-13", "INV-13", "a1", "bob", "QA_MANAGER");

        InvestigationView view = investigationService.getInvestigationView("WB-INV-13", "INV-13");
        assertEquals(1, view.currentBreachIntervals().size());
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"),
                view.currentBreachIntervals().get(0).getStart());
        assertEquals(Instant.parse("2026-01-01T10:10:00Z"),
                view.currentBreachIntervals().get(0).getEnd());
        assertEquals(2, view.evidenceEvents().size());
        assertEquals(2, view.temperatureTimeline().size());
        assertEquals(1, view.approvals().size());
        assertEquals("bob", view.approvals().get(0).getApprover());
        assertEquals("QA_MANAGER", view.approvals().get(0).getRole());
        assertEquals(UnitStatus.QUARANTINED, view.units().get(0).getStatus());
        assertEquals(600, view.investigation().getLockedMaxBreachSeconds());
        assertFalse(view.investigation().isFinished());
    }

    @Test
    void registeredUnitAfterQuarantineStartsQuarantinedAndIdempotent() {
        coldChainService.createShipment("WB-INV-14", 2.0, 8.0, 600, "alice",
                Instant.parse("2026-01-01T08:00:00Z"));
        coldChainService.recordTemperatureEvent("WB-INV-14", "e1",
                Instant.parse("2026-01-01T10:00:00Z"), 10.0);
        coldChainService.recordTemperatureEvent("WB-INV-14", "e2",
                Instant.parse("2026-01-01T10:10:00Z"), 10.0);

        PackingUnit lateUnit = coldChainService.registerPackingUnit("WB-INV-14", "late");
        assertEquals(UnitStatus.QUARANTINED, lateUnit.getStatus());

        PackingUnit replay = coldChainService.registerPackingUnit("WB-INV-14", "late");
        assertEquals(lateUnit.getId(), replay.getId());
        assertEquals(1, coldChainService.listPackingUnits("WB-INV-14").size());
    }

    @Test
    void releaseAfterExpiryIsRejected() {
        setupQuarantinedShipment("WB-INV-15", "u1");
        investigationService.createInvestigation("WB-INV-15", "INV-15", null, null);
        investigationService.conclude("WB-INV-15", "INV-15", "d1",
                DispositionType.CONDITIONAL_RELEASE, Instant.now().plusMillis(50), "限工业用途", "qa");
        investigationService.approve("WB-INV-15", "INV-15", "a1", "bob", "QA_MANAGER");
        investigationService.approve("WB-INV-15", "INV-15", "a2", "carol", "REGULATORY");

        // 等待有效期过期
        try {
            Thread.sleep(100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertErrorCode("RELEASE_EXPIRED", () ->
                investigationService.executeRelease("WB-INV-15", "INV-15", "rel1", "ops"));
        assertEquals(UnitStatus.QUARANTINED, unitStatusMap("WB-INV-15").get("u1"));
    }

    private Map<String, UnitStatus> unitStatusMap(String waybill) {
        return coldChainService.listPackingUnits(waybill).stream()
                .collect(Collectors.toMap(PackingUnit::getUnitCode, PackingUnit::getStatus));
    }
}
