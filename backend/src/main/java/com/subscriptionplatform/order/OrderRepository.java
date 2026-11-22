package com.subscriptionplatform.order;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    boolean existsBySubscriptionIdAndRunDate(Long subscriptionId, LocalDate runDate);

    @Override
    @EntityGraph(attributePaths = {"subscription", "subscription.customer", "subscription.plan"})
    Page<Order> findAll(Specification<Order> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"subscription", "subscription.customer", "subscription.plan"})
    Optional<Order> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"subscription", "subscription.customer", "subscription.plan"})
    List<Order> findByIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = {"subscription", "subscription.customer", "subscription.plan"})
    List<Order> findBySubscriptionIdInOrderByRunDateDesc(Collection<Long> subscriptionIds);

    List<Order> findBySubscriptionIdOrderByRunDateAsc(Long subscriptionId);
}
