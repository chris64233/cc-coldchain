package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.PackagingUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PackagingUnitRepository extends JpaRepository<PackagingUnit, Long> {

    List<PackagingUnit> findByShipmentIdOrderByIdAsc(Long shipmentId);

    Optional<PackagingUnit> findByShipmentIdAndUnitCode(Long shipmentId, String unitCode);

    boolean existsByShipmentIdAndUnitCode(Long shipmentId, String unitCode);
}
