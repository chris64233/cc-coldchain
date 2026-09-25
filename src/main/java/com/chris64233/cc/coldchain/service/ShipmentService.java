package com.chris64233.cc.coldchain.service;

import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.HandoverStatus;
import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;
import com.chris64233.cc.coldchain.domain.TemperatureEvent;
import com.chris64233.cc.coldchain.exception.ApiException;
import com.chris64233.cc.coldchain.repo.HandoverRecordRepository;
import com.chris64233.cc.coldchain.repo.ShipmentRepository;
import com.chris64233.cc.coldchain.repo.TemperatureEventRepository;
import com.chris64233.cc.coldchain.web.dto.CreateShipmentRequest;
import com.chris64233.cc.coldchain.web.dto.HandoverResponse;
import com.chris64233.cc.coldchain.web.dto.InitiateHandoverRequest;
import com.chris64233.cc.coldchain.web.dto.ShipmentResponse;
import com.chris64233.cc.coldchain.web.dto.TemperatureEventRequest;
import com.chris64233.cc.coldchain.web.dto.TemperatureEventResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final TemperatureEventRepository temperatureEventRepository;
    private final HandoverRecordRepository handoverRecordRepository;

    public ShipmentService(ShipmentRepository shipmentRepository,
                           TemperatureEventRepository temperatureEventRepository,
                           HandoverRecordRepository handoverRecordRepository) {
        this.shipmentRepository = shipmentRepository;
        this.temperatureEventRepository = temperatureEventRepository;
        this.handoverRecordRepository = handoverRecordRepository;
    }

    @Transactional
    public ShipmentResponse createShipment(CreateShipmentRequest request) {
        if (request.tempMin() >= request.tempMax()) {
            throw ApiException.badRequest("温度下限必须小于温度上限");
        }
        Shipment shipment = new Shipment(
                request.waybillNo().trim(),
                request.tempMin(),
                request.tempMax(),
                request.maxBreachMinutes(),
                request.initialHolder().trim(),
                request.departureTime());
        try {
            shipmentRepository.saveAndFlush(shipment);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("运单号已存在: " + request.waybillNo());
        }
        return ShipmentResponse.from(shipment);
    }

    @Transactional
    public TemperatureEventResponse recordTemperatureEvent(String waybillNo, TemperatureEventRequest request) {
        Shipment shipment = lockShipment(waybillNo);

        var existing = temperatureEventRepository
                .findByShipmentIdAndDeviceEventId(shipment.getId(), request.deviceEventId());
        if (existing.isPresent()) {
            TemperatureEvent event = existing.get();
            if (!event.sameContentAs(request.sampledAt(), request.temperature())) {
                throw ApiException.conflict("设备事件号已存在且内容不一致: " + request.deviceEventId());
            }
            return TemperatureEventResponse.from(event);
        }

        TemperatureEvent event = new TemperatureEvent(
                shipment, request.deviceEventId(), request.sampledAt(), request.temperature());
        try {
            temperatureEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("设备事件号已存在: " + request.deviceEventId());
        }

        if (shipment.getStatus() == ShipmentStatus.ACTIVE && breachLimitReached(shipment)) {
            shipment.quarantine();
            shipmentRepository.save(shipment);
        }
        return TemperatureEventResponse.from(event);
    }

    @Transactional
    public HandoverResponse initiateHandover(String waybillNo, InitiateHandoverRequest request) {
        Shipment shipment = lockShipment(waybillNo);
        requireActive(shipment, "隔离货物不能发起交接");
        if (!shipment.getCurrentHolder().equals(request.fromHolder())) {
            throw ApiException.forbidden("只有当前持有人可以发起交接");
        }
        if (request.fromHolder().equals(request.toHolder())) {
            throw ApiException.badRequest("接收方不能与当前持有人相同");
        }
        if (handoverRecordRepository.existsByShipmentIdAndStatus(shipment.getId(), HandoverStatus.PENDING)) {
            throw ApiException.conflict("存在待确认的交接，不能重复发起");
        }
        HandoverRecord record = new HandoverRecord(shipment, request.fromHolder(), request.toHolder());
        handoverRecordRepository.saveAndFlush(record);
        return HandoverResponse.from(record);
    }

    @Transactional
    public HandoverResponse confirmHandover(String waybillNo, Long handoverId, String confirmedBy) {
        Shipment shipment = lockShipment(waybillNo);
        HandoverRecord record = handoverRecordRepository
                .findByIdAndShipmentId(handoverId, shipment.getId())
                .orElseThrow(() -> ApiException.notFound("交接记录不存在: " + handoverId));

        requireActive(shipment, "隔离货物不能确认交接");
        if (record.getStatus() != HandoverStatus.PENDING) {
            throw ApiException.conflict("交接已确认，不能重复确认");
        }
        if (!record.getToHolder().equals(confirmedBy)) {
            throw ApiException.forbidden("只有指定接收方可以确认交接");
        }

        record.confirm();
        shipment.transferTo(record.getToHolder());
        handoverRecordRepository.save(record);
        shipmentRepository.save(shipment);
        return HandoverResponse.from(record);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse getShipment(String waybillNo) {
        return ShipmentResponse.from(findShipment(waybillNo));
    }

    @Transactional(readOnly = true)
    public List<TemperatureEventResponse> listTemperatureEvents(String waybillNo) {
        Shipment shipment = findShipment(waybillNo);
        return temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId())
                .stream().map(TemperatureEventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<HandoverResponse> listHandovers(String waybillNo) {
        Shipment shipment = findShipment(waybillNo);
        return handoverRecordRepository.findByShipmentIdOrderByInitiatedAtAscIdAsc(shipment.getId())
                .stream().map(HandoverResponse::from).toList();
    }

    private boolean breachLimitReached(Shipment shipment) {
        List<TemperatureEvent> events =
                temperatureEventRepository.findByShipmentIdOrderBySampledAtAscIdAsc(shipment.getId());
        Instant breachStart = null;
        for (TemperatureEvent event : events) {
            boolean outOfRange = event.getTemperature() < shipment.getTempMin()
                    || event.getTemperature() > shipment.getTempMax();
            if (!outOfRange) {
                breachStart = null;
                continue;
            }
            if (breachStart == null) {
                breachStart = event.getSampledAt();
            }
            Duration breach = Duration.between(breachStart, event.getSampledAt());
            if (breach.compareTo(Duration.ofMinutes(shipment.getMaxBreachMinutes())) >= 0) {
                return true;
            }
        }
        return false;
    }

    private void requireActive(Shipment shipment, String message) {
        if (shipment.getStatus() != ShipmentStatus.ACTIVE) {
            throw ApiException.conflict(message);
        }
    }

    private Shipment lockShipment(String waybillNo) {
        return shipmentRepository.findByWaybillNoForUpdate(waybillNo)
                .orElseThrow(() -> ApiException.notFound("运单不存在: " + waybillNo));
    }

    private Shipment findShipment(String waybillNo) {
        return shipmentRepository.findByWaybillNo(waybillNo)
                .orElseThrow(() -> ApiException.notFound("运单不存在: " + waybillNo));
    }
}
