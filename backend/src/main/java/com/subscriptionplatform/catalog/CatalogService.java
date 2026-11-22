package com.subscriptionplatform.catalog;

import com.subscriptionplatform.common.NotFoundException;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.customer.CustomerRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for reference data: plans and customers. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final PlanRepository plans;
    private final CustomerRepository customers;

    public CatalogService(PlanRepository plans, CustomerRepository customers) {
        this.plans = plans;
        this.customers = customers;
    }

    public List<Plan> plans() {
        return plans.findAllByOrderByNameAsc();
    }

    public List<Customer> customers() {
        return customers.findAllByOrderByNameAsc();
    }

    public Plan requirePlan(String code) {
        return plans.findByCode(code).orElseThrow(() -> new NotFoundException("Plan", code));
    }
}
