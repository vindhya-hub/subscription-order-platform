package com.subscriptionplatform.subscription;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** Ids of subscriptions in {@code status} whose next run date is on or before {@code asOf}. */
    @Query("""
            select s.id from Subscription s
            where s.status = :status and s.nextRunDate <= :asOf
            order by s.nextRunDate, s.id
            """)
    List<Long> findDueIds(@Param("status") SubscriptionStatus status, @Param("asOf") LocalDate asOf);

    @EntityGraph(attributePaths = {"customer", "plan"})
    List<Subscription> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = {"customer", "plan"})
    List<Subscription> findByStatusOrderByIdDesc(SubscriptionStatus status);

    @EntityGraph(attributePaths = {"customer", "plan"})
    Optional<Subscription> findWithDetailsById(Long id);
}
