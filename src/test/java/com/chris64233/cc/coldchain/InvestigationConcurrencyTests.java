package com.chris64233.cc.coldchain;

import com.chris64233.cc.coldchain.domain.DispositionType;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
import com.chris64233.cc.coldchain.domain.UnitStatus;
import com.chris64233.cc.coldchain.service.ApiException;
import com.chris64233.cc.coldchain.service.ColdChainService;
import com.chris64233.cc.coldchain.service.InvestigationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class InvestigationConcurrencyTests {

    @Autowired
    private ColdChainService coldChainService;

    @Autowired
    private InvestigationService investigationService;

    private String prepareApprovedRelease(String waybill) {
        coldChainService.createShipment(waybill, 2.0, 8.0, 600, "alice",
                Instant.parse("2026-01-01T08:00:00Z"));
        coldChainService.registerPackingUnit(waybill, "u1");
        coldChainService.recordTemperatureEvent(waybill, "e1",
                Instant.parse("2026-01-01T10:00:00Z"), 10.0);
        coldChainService.recordTemperatureEvent(waybill, "e2",
                Instant.parse("2026-01-01T10:10:00Z"), 10.0);
        investigationService.createInvestigation(waybill, "INV-" + waybill, null, null);
        investigationService.conclude(waybill, "INV-" + waybill, "d1",
                DispositionType.CONDITIONAL_RELEASE, Instant.now().plus(1, ChronoUnit.DAYS),
                "仅限工业用途", "qa");
        investigationService.approve(waybill, "INV-" + waybill, "a1", "bob", "QA_MANAGER");
        investigationService.approve(waybill, "INV-" + waybill, "a2", "carol", "REGULATORY");
        return "INV-" + waybill;
    }

    @Test
    void releaseRacingWithNewBreachEventNeverReleasesStaleGoods() throws Exception {
        String waybill = "WB-INV-RACE-1";
        String investigationNo = prepareApprovedRelease(waybill);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> breachTask = executor.submit(() -> {
                ready.countDown();
                await(start);
                // 早于原区间的越界样本，扩大已确认异常区间
                coldChainService.recordTemperatureEvent(waybill, "e0",
                        Instant.parse("2026-01-01T09:55:00Z"), 10.0);
                return null;
            });
            Future<?> releaseTask = executor.submit(() -> {
                ready.countDown();
                await(start);
                investigationService.executeRelease(waybill, investigationNo, "rel1", "ops");
                return null;
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            breachTask.get();
            Object releaseResult = null;
            ApiException releaseError = null;
            try {
                releaseResult = releaseTask.get();
            } catch (Exception ex) {
                releaseError = findApiException(ex);
            }

            Investigation investigation = investigationService.listInvestigations(waybill).get(0);
            if (releaseResult != null) {
                // 放行先提交：单元已放行，后到事件不应再把该单元改回隔离/失效结论
                assertEquals(InvestigationStatus.COMPLETED, investigation.getStatus());
                assertEquals(UnitStatus.RELEASED,
                        coldChainService.listPackingUnits(waybill).get(0).getStatus());
            } else {
                // 越界事件先提交（含等待行锁后失败）：放行必须失败，单元保持隔离
                if (releaseError != null) {
                    assertEquals("NEW_BREACH_AFTER_APPROVAL", releaseError.getCode());
                }
                assertEquals(InvestigationStatus.SUPERSEDED, investigation.getStatus());
                assertEquals(UnitStatus.QUARANTINED,
                        coldChainService.listPackingUnits(waybill).get(0).getStatus());
                assertEquals(2, coldChainService.getShipment(waybill).getEvidenceVersion());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReleasesExecuteExactlyOnce() throws Exception {
        String waybill = "WB-INV-RACE-2";
        String investigationNo = prepareApprovedRelease(waybill);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Runnable release = () -> {
                ready.countDown();
                await(start);
                investigationService.executeRelease(waybill, investigationNo, "rel1", "ops");
            };
            Future<?> first = executor.submit(release);
            Future<?> second = executor.submit(release);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get();
            second.get();

            Investigation investigation = investigationService.listInvestigations(waybill).get(0);
            assertEquals(InvestigationStatus.COMPLETED, investigation.getStatus());
            assertEquals(UnitStatus.RELEASED,
                    coldChainService.listPackingUnits(waybill).get(0).getStatus());
            // CONCLUSION + EXECUTION 各一条，幂等重放不产生重复
            assertEquals(2, investigationService.getInvestigationView(waybill, investigationNo)
                    .dispositionEvents().size());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static ApiException findApiException(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ApiException api) {
                return api;
            }
        }
        return null;
    }
}
