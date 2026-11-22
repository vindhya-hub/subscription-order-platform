package com.subscriptionplatform.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    boolean existsByEmail(String email);

    List<Customer> findAllByOrderByNameAsc();
}
