package com.subscriptionplatform.graphql;

import com.subscriptionplatform.catalog.CatalogService;
import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.customer.Customer;
import java.util.List;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class CatalogGraphQlController {

    private final CatalogService catalog;

    public CatalogGraphQlController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @QueryMapping
    public List<Plan> plans() {
        return catalog.plans();
    }

    @QueryMapping
    public List<Customer> customers() {
        return catalog.customers();
    }
}
