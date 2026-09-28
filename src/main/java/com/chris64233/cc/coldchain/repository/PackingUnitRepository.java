package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.PackingUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PackingUnitRepository extends JpaRepository<PackingUnit, Long> {

    List<PackingUnit> findByShipmentIdOrderByIdAsc(Long shipmentId);

    Optional<PackingUnit> findByShipmentIdAndUnitCode(Long shipmentId, String unitCode);

    List<PackingUnit> findByShipmentIdAndUnitCodeIn(Long shipmentId, List<String> unitCodes);
}
