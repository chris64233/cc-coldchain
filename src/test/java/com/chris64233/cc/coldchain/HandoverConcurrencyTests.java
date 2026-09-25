package com.chris64233.cc.coldchain;

import com.chris64233.cc.coldchain.domain.HandoverStatus;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.exception.ApiException;
import com.chris64233.cc.coldchain.repo.HandoverRecordRepository;
import com.chris64233.cc.coldchain.service.ShipmentService;
import com.chris64233.cc.coldchain.web.dto.ConfirmHandoverRequest;
import com.chris64233.cc.coldchain.web.dto.CreateShipmentRequest;
import com.chris64233.cc.coldchain.web.dto.HandoverResponse;
import com.chris64233.cc.coldchain.web.dto.InitiateHandoverRequest;
import com.chris64233.cc.coldchain.web.dto.TemperatureEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HandoverConcurrencyTests {

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private HandoverRecordRepository handoverRecordRepository;

    private String createShipment(String waybillNo) {
        shipmentService.createShipment(new CreateShipmentRequest(
                waybillNo, 2.0, 8.0, 30L, "alice", Instant.parse("2026-01-01T00:00:00Z")));
        return waybillNo;
    }

    @Test
    void concurrentDuplicateConfirmsProduceSingleSuccess() throws Exception {
        String waybillNo = createShipment("WB-CONC-1");
        HandoverResponse handover = shipmentService.initiateHandover(
                waybillNo, new InitiateHandoverRequest("alice", "bob"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    shipmentService.confirmHandover(waybillNo, handover.id(), "bob");
                    successes.incrementAndGet();
                } catch (ApiException ex) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(shipmentService.getShipment(waybillNo).currentHolder()).isEqualTo("bob");
        assertThat(shipmentService.listHandovers(waybillNo)).hasSize(1);
        assertThat(shipmentService.listHandovers(waybillNo).get(0).status())
                .isEqualTo(HandoverStatus.CONFIRMED);
    }

    @Test
    void quarantiningEventAndConcurrentConfirmStayConsistent() throws Exception {
        String waybillNo = createShipment("WB-CONC-2");
        HandoverResponse handover = shipmentService.initiateHandover(
                waybillNo, new InitiateHandoverRequest("alice", "bob"));

        Runnable quarantineTask = () -> {
            shipmentService.recordTemperatureEvent(waybillNo, new TemperatureEventRequest(
                    "e1", Instant.parse("2026-01-01T00:00:00Z"), 10.0));
            shipmentService.recordTemperatureEvent(waybillNo, new TemperatureEventRequest(
                    "e2", Instant.parse("2026-01-01T00:40:00Z"), 10.0));
        };
        Runnable confirmTask = () -> {
            try {
                shipmentService.confirmHandover(waybillNo, handover.id(), "bob");
            } catch (ApiException ignored) {
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> quarantineFuture = pool.submit(quarantineTask);
        Future<?> confirmFuture = pool.submit(confirmTask);
        quarantineFuture.get(30, TimeUnit.SECONDS);
        confirmFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        var shipment = shipmentService.getShipment(waybillNo);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.QUARANTINED);
        var handovers = shipmentService.listHandovers(waybillNo);
        assertThat(handovers).hasSize(1);
        if (handovers.get(0).status() == HandoverStatus.CONFIRMED) {
            assertThat(shipment.currentHolder()).isEqualTo("bob");
        } else {
            assertThat(shipment.currentHolder()).isEqualTo("alice");
        }
        assertThat(handoverRecordRepository
                .existsByShipmentIdAndStatus(shipment.id(), HandoverStatus.PENDING)
                && handovers.get(0).status() == HandoverStatus.CONFIRMED).isFalse();
    }
}
