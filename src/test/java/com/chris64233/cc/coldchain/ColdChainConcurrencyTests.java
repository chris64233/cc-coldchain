package com.chris64233.cc.coldchain;

import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.HandoverStatus;
import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.service.ApiException;
import com.chris64233.cc.coldchain.service.ColdChainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ColdChainConcurrencyTests {

    @Autowired
    private ColdChainService service;

    @Test
    void quarantineAndConfirmRaceStaysConsistent() throws Exception {
        String waybill = "WB-RACE-1";
        service.createShipment(waybill, 2.0, 8.0, 600, "alice", Instant.parse("2026-01-01T08:00:00Z"));
        service.recordTemperatureEvent(waybill, "e1", Instant.parse("2026-01-01T10:00:00Z"), 10.0);
        HandoverRecord handover = service.initiateHandover(waybill, "alice", "bob");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> quarantineTask = executor.submit(() -> {
                ready.countDown();
                await(start);
                service.recordTemperatureEvent(waybill, "e2",
                        Instant.parse("2026-01-01T10:10:00Z"), 10.0);
                return null;
            });
            Future<HandoverRecord> confirmTask = executor.submit(() -> {
                ready.countDown();
                await(start);
                return service.confirmHandover(waybill, handover.getId(), "bob");
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            quarantineTask.get();
            HandoverRecord confirmResult;
            try {
                confirmResult = confirmTask.get();
            } catch (Exception ex) {
                confirmResult = null;
            }

            Shipment shipment = service.getShipment(waybill);
            assertEquals(ShipmentStatus.QUARANTINED, shipment.getStatus());
            assertEquals(2, service.listTemperatureEvents(waybill).size());
            List<HandoverRecord> handovers = service.listHandovers(waybill);
            assertEquals(1, handovers.size());
            if (confirmResult != null) {
                assertEquals(HandoverStatus.CONFIRMED, handovers.get(0).getStatus());
                assertEquals("bob", shipment.getCurrentHolder());
            } else {
                assertEquals(HandoverStatus.PENDING, handovers.get(0).getStatus());
                assertEquals("alice", shipment.getCurrentHolder());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateConcurrentConfirmDoesNotDuplicateOrMoveHolderTwice() throws Exception {
        String waybill = "WB-RACE-2";
        service.createShipment(waybill, 2.0, 8.0, 600, "alice", Instant.parse("2026-01-01T08:00:00Z"));
        HandoverRecord handover = service.initiateHandover(waybill, "alice", "bob");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Runnable confirm = () -> {
                ready.countDown();
                await(start);
                service.confirmHandover(waybill, handover.getId(), "bob");
            };
            Future<?> first = executor.submit(confirm);
            Future<?> second = executor.submit(confirm);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get();
            second.get();

            Shipment shipment = service.getShipment(waybill);
            assertEquals("bob", shipment.getCurrentHolder());
            List<HandoverRecord> handovers = service.listHandovers(waybill);
            assertEquals(1, handovers.size());
            assertEquals(HandoverStatus.CONFIRMED, handovers.get(0).getStatus());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lateHistoricalEventStillQuarantinesAfterHandover() {
        String waybill = "WB-RACE-3";
        service.createShipment(waybill, 2.0, 8.0, 600, "alice", Instant.parse("2026-01-01T08:00:00Z"));
        service.recordTemperatureEvent(waybill, "e1", Instant.parse("2026-01-01T10:00:00Z"), 10.0);
        HandoverRecord handover = service.initiateHandover(waybill, "alice", "bob");
        service.confirmHandover(waybill, handover.getId(), "bob");

        service.recordTemperatureEvent(waybill, "e2", Instant.parse("2026-01-01T10:10:00Z"), 10.0);

        Shipment shipment = service.getShipment(waybill);
        assertEquals("bob", shipment.getCurrentHolder());
        assertEquals(ShipmentStatus.QUARANTINED, shipment.getStatus());
        try {
            service.initiateHandover(waybill, "bob", "carol");
        } catch (ApiException ex) {
            assertEquals("SHIPMENT_QUARANTINED", ex.getCode());
            return;
        }
        throw new AssertionError("隔离货物不应允许发起交接");
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
