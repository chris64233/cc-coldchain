package com.chris64233.cc.coldchain.service;

import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.HandoverStatus;
import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.domain.TemperatureEvent;
import com.chris64233.cc.coldchain.repository.HandoverRecordRepository;
import com.chris64233.cc.coldchain.repository.ShipmentRepository;
import com.chris64233.cc.coldchain.repository.TemperatureEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ColdChainService {

    private final ShipmentRepository shipmentRepository;
    private final TemperatureEventRepository temperatureEventRepository;
    private final HandoverRecordRepository handoverRecordRepository;
    private final InvestigationService investigationService;

    public ColdChainService(ShipmentRepository shipmentRepository,
                            TemperatureEventRepository temperatureEventRepository,
                            HandoverRecordRepository handoverRecordRepository,
                            InvestigationService investigationService) {
        this.shipmentRepository = shipmentRepository;
        this.temperatureEventRepository = temperatureEventRepository;
        this.handoverRecordRepository = handoverRecordRepository;
        this.investigationService = investigationService;
    }

    @Transactional
    public Shipment createShipment(String waybillNo, double tempMin, double tempMax,
                                   long maxBreachSeconds, String initialHolder, Instant departureTime) {
        if (!(tempMin < tempMax)) {
            throw ApiException.badRequest("INVALID_TEMPERATURE_RANGE", "温度下限必须小于上限");
        }
        if (maxBreachSeconds <= 0) {
            throw ApiException.badRequest("INVALID_BREACH_DURATION", "允许的连续越界时长必须大于 0 秒");
        }
        Shipment shipment = new Shipment(waybillNo, tempMin, tempMax, maxBreachSeconds, initialHolder, departureTime);
        try {
            return shipmentRepository.saveAndFlush(shipment);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("DUPLICATE_WAYBILL", "运单号已存在: " + waybillNo);
        }
    }

    @Transactional(readOnly = true)
    public Shipment getShipment(String waybillNo) {
        return shipmentRepository.findByWaybillNo(waybillNo)
                .orElseThrow(() -> ApiException.notFound("运单不存在: " + waybillNo));
    }

    @Transactional
    public TemperatureEvent recordTemperatureEvent(String waybillNo, String eventId,
                                                   Instant sampledAt, double temperature) {
        Shipment shipment = lockShipment(waybillNo);
        var existing = temperatureEventRepository.findByShipmentIdAndEventId(shipment.getId(), eventId);
        if (existing.isPresent()) {
            TemperatureEvent event = existing.get();
            if (event.getSampledAt().equals(sampledAt)
                    && Double.compare(event.getTemperature(), temperature) == 0) {
                return event;
            }
            throw ApiException.conflict("EVENT_CONFLICT",
                    "事件号 " + eventId + " 已存在但内容不一致");
        }
        TemperatureEvent event = temperatureEventRepository.saveAndFlush(
                new TemperatureEvent(shipment, eventId, sampledAt, temperature));
        recomputeQuarantine(shipment);
        // 即使货物已隔离，事件始终接收；后到的历史事件若扩大异常区间，
        // 必须使未完成的调查结论失效。
        if (shipment.getStatus() == ShipmentStatus.QUARANTINED) {
            investigationService.invalidateIfEvidenceExpanded(shipment, currentSnapshot(shipment));
        }
        return event;
    }

    @Transactional(readOnly = true)
    public List<TemperatureEvent> listTemperatureEvents(String waybillNo) {
        Shipment shipment = getShipment(waybillNo);
        return temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId());
    }

    @Transactional
    public HandoverRecord initiateHandover(String waybillNo, String fromHolder, String toHolder) {
        Shipment shipment = lockShipment(waybillNo);
        if (shipment.getStatus() == ShipmentStatus.QUARANTINED
                || shipment.getStatus() == ShipmentStatus.CONDEMNED) {
            throw ApiException.conflict("SHIPMENT_QUARANTINED", "隔离/报废货物不能发起交接");
        }
        if (!shipment.getCurrentHolder().equals(fromHolder)) {
            throw ApiException.conflict("NOT_CURRENT_HOLDER", "只有当前持有人可以发起交接");
        }
        if (fromHolder.equals(toHolder)) {
            throw ApiException.badRequest("INVALID_RECEIVER", "接收方不能与当前持有人相同");
        }
        if (handoverRecordRepository.existsByShipmentIdAndStatus(shipment.getId(), HandoverStatus.PENDING)) {
            throw ApiException.conflict("HANDOVER_PENDING", "存在待确认的交接，不能重复发起");
        }
        return handoverRecordRepository.saveAndFlush(new HandoverRecord(shipment, fromHolder, toHolder));
    }

    @Transactional
    public HandoverRecord confirmHandover(String waybillNo, Long handoverId, String confirmedBy) {
        Shipment shipment = lockShipment(waybillNo);
        HandoverRecord handover = handoverRecordRepository.findById(handoverId)
                .filter(record -> record.getShipment().getId().equals(shipment.getId()))
                .orElseThrow(() -> ApiException.notFound("交接记录不存在: " + handoverId));
        if (handover.getStatus() == HandoverStatus.CONFIRMED) {
            if (handover.getToHolder().equals(confirmedBy)) {
                return handover;
            }
            throw ApiException.forbidden("NOT_DESIGNATED_RECEIVER", "只有待确认交接的接收方可以确认");
        }
        if (!handover.getToHolder().equals(confirmedBy)) {
            throw ApiException.forbidden("NOT_DESIGNATED_RECEIVER", "只有待确认交接的接收方可以确认");
        }
        if (shipment.getStatus() == ShipmentStatus.QUARANTINED
                || shipment.getStatus() == ShipmentStatus.CONDEMNED) {
            throw ApiException.conflict("SHIPMENT_QUARANTINED", "隔离/报废货物不能确认交接");
        }
        handover.confirm(Instant.now());
        shipment.setCurrentHolder(handover.getToHolder());
        return handoverRecordRepository.saveAndFlush(handover);
    }

    @Transactional(readOnly = true)
    public List<HandoverRecord> listHandovers(String waybillNo) {
        Shipment shipment = getShipment(waybillNo);
        return handoverRecordRepository.findByShipmentIdOrderByInitiatedAtAscIdAsc(shipment.getId());
    }

    private Shipment lockShipment(String waybillNo) {
        return shipmentRepository.findByWaybillNoForUpdate(waybillNo)
                .orElseThrow(() -> ApiException.notFound("运单不存在: " + waybillNo));
    }

    private ExcursionCalculator.Snapshot currentSnapshot(Shipment shipment) {
        List<TemperatureEvent> events =
                temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId());
        return ExcursionCalculator.analyze(events, shipment.getTempMin(), shipment.getTempMax(),
                shipment.getMaxBreachSeconds());
    }

    private void recomputeQuarantine(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.IN_TRANSIT) {
            return;
        }
        if (!currentSnapshot(shipment).isEmpty()) {
            shipment.setStatus(ShipmentStatus.QUARANTINED);
        }
    }
}
