package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.TemperatureEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemperatureEventRepository extends JpaRepository<TemperatureEvent, Long> {

    Optional<TemperatureEvent> findByShipmentIdAndEventId(Long shipmentId, String eventId);

    List<TemperatureEvent> findByShipmentIdOrderBySampledAtAscIdAsc(Long shipmentId);
}
