package com.chris64233.cc.coldchain.web;

import com.chris64233.cc.coldchain.service.ShipmentService;
import com.chris64233.cc.coldchain.web.dto.ConfirmHandoverRequest;
import com.chris64233.cc.coldchain.web.dto.CreateShipmentRequest;
import com.chris64233.cc.coldchain.web.dto.HandoverResponse;
import com.chris64233.cc.coldchain.web.dto.InitiateHandoverRequest;
import com.chris64233.cc.coldchain.web.dto.ShipmentResponse;
import com.chris64233.cc.coldchain.web.dto.TemperatureEventRequest;
import com.chris64233.cc.coldchain.web.dto.TemperatureEventResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentResponse createShipment(@Valid @RequestBody CreateShipmentRequest request) {
        return shipmentService.createShipment(request);
    }

    @GetMapping("/{waybillNo}")
    public ShipmentResponse getShipment(@PathVariable String waybillNo) {
        return shipmentService.getShipment(waybillNo);
    }

    @PostMapping("/{waybillNo}/temperature-events")
    @ResponseStatus(HttpStatus.CREATED)
    public TemperatureEventResponse recordTemperatureEvent(@PathVariable String waybillNo,
                                                           @Valid @RequestBody TemperatureEventRequest request) {
        return shipmentService.recordTemperatureEvent(waybillNo, request);
    }

    @GetMapping("/{waybillNo}/temperature-events")
    public List<TemperatureEventResponse> listTemperatureEvents(@PathVariable String waybillNo) {
        return shipmentService.listTemperatureEvents(waybillNo);
    }

    @PostMapping("/{waybillNo}/handovers")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoverResponse initiateHandover(@PathVariable String waybillNo,
                                             @Valid @RequestBody InitiateHandoverRequest request) {
        return shipmentService.initiateHandover(waybillNo, request);
    }

    @PostMapping("/{waybillNo}/handovers/{handoverId}/confirm")
    public HandoverResponse confirmHandover(@PathVariable String waybillNo,
                                            @PathVariable Long handoverId,
                                            @Valid @RequestBody ConfirmHandoverRequest request) {
        return shipmentService.confirmHandover(waybillNo, handoverId, request.confirmedBy());
    }

    @GetMapping("/{waybillNo}/handovers")
    public List<HandoverResponse> listHandovers(@PathVariable String waybillNo) {
        return shipmentService.listHandovers(waybillNo);
    }
}
