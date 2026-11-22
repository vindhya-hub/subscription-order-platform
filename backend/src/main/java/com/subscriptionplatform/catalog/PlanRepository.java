package com.subscriptionplatform.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findAllByOrderByNameAsc();

    Optional<Plan> findByCode(String code);
}
