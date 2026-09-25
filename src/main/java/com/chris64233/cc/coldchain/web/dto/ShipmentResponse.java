package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.Shipment;
import com.chris64233.cc.coldchain.domain.ShipmentStatus;

import java.time.Instant;

public record ShipmentResponse(
        Long id,
        String waybillNo,
        double tempMin,
        double tempMax,
        long maxBreachMinutes,
        String currentHolder,
        Instant departureTime,
        ShipmentStatus status,
        Instant createdAt) {

    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getId(),
                shipment.getWaybillNo(),
                shipment.getTempMin(),
                shipment.getTempMax(),
                shipment.getMaxBreachMinutes(),
                shipment.getCurrentHolder(),
                shipment.getDepartureTime(),
                shipment.getStatus(),
                shipment.getCreatedAt());
    }
}
