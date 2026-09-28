package com.chris64233.cc.coldchain;

import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationDecision;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
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
    private ColdChainService coldChain;

    @Autowired
    private InvestigationService investigations;

    private static final Instant T0 = Instant.parse("2026-05-01T08:00:00Z");

    /** 放行处置与新越界事件并发：任一提交顺序下都不能放行不满足条件的货物。 */
    @Test
    void releaseRacingWithNewExcursionNeverReleasesUnqualifiedGoods() throws Exception {
        String waybill = "WB-INV-RACE-1";
        coldChain.createShipment(waybill, 2.0, 8.0, 600, "alice", T0);
        // 闭合越界区间 10:00..10:20
        coldChain.recordTemperatureEvent(waybill, "e1", T0.plus(2, ChronoUnit.HOURS), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e2", T0.plus(2, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
        coldChain.recordTemperatureEvent(waybill, "e3", T0.plus(2, ChronoUnit.HOURS).plus(20, ChronoUnit.MINUTES), 5.0);
        investigations.createInvestigation(waybill, "INV-RACE-1");
        investigations.submitConclusion("INV-RACE-1",
                InvestigationDecision.CONDITIONAL_RELEASE, null,
                Instant.now().plus(1, ChronoUnit.HOURS), "限用", "qa");
        investigations.approve("INV-RACE-1", "AR-1", "alice", "QA");
        investigations.approve("INV-RACE-1", "AR-2", "bob", "SAFETY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> excursionTask = executor.submit(() -> {
                ready.countDown();
                await(start);
                // 新的独立达阈区间 12:00..12:10
                coldChain.recordTemperatureEvent(waybill, "e4", T0.plus(4, ChronoUnit.HOURS), 10.0);
                coldChain.recordTemperatureEvent(waybill, "e5", T0.plus(4, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), 10.0);
                return null;
            });
            Future<?> releaseFuture = executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    return investigations.release("INV-RACE-1", "DR-1", "DEFAULT", "w");
                } catch (ApiException ex) {
                    return ex;
                }
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            excursionTask.get();
            Object releaseResult = releaseFuture.get();

            Investigation inv = investigations.getInvestigation("INV-RACE-1");
            UnitStatus unitStatus = investigations.listUnits(waybill).get(0).getStatus();
            ShipmentStatus shipmentStatus = coldChain.getShipment(waybill).getStatus();
            if (releaseResult instanceof ApiException ex) {
                // 新越界事件先提交：放行必须被拒绝，结论失效、货物继续隔离
                assertEquals("RELEASE_REJECTED_NEW_EXCURSION", ex.getCode());
                assertEquals(InvestigationStatus.INVALIDATED, inv.getStatus());
                assertEquals(UnitStatus.HELD, unitStatus);
                assertEquals(ShipmentStatus.QUARANTINED, shipmentStatus);
            } else {
                // 放行先提交：放行时刻证据未扩大，合法放行；事件作为审计记录后到
                assertEquals(InvestigationStatus.COMPLETED, inv.getStatus());
                assertEquals(UnitStatus.RELEASED, unitStatus);
                assertEquals(ShipmentStatus.RELEASED, shipmentStatus);
                assertEquals(5, coldChain.listTemperatureEvents(waybill).size());
            }
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
}
