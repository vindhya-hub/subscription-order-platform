package com.subscriptionplatform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.catalog.PlanRepository;
import com.subscriptionplatform.common.BusinessRuleException;
import com.subscriptionplatform.common.NotFoundException;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.customer.CustomerRepository;
import com.subscriptionplatform.subscription.CreateSubscriptionInput.NewCustomerInput;
import com.subscriptionplatform.support.Entities;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-02T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);

    @Mock
    private SubscriptionRepository subscriptions;
    @Mock
    private CustomerRepository customers;
    @Mock
    private PlanRepository plans;

    private SubscriptionService service;
    private final Plan coffee = Entities.plan(1, "COFFEE", "16.50");
    private final Customer ava = Entities.customer(1, "Ava Patel");

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(subscriptions, customers, plans, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(plans.findById(1L)).thenReturn(Optional.of(coffee));
        lenient().when(customers.findById(1L)).thenReturn(Optional.of(ava));
        lenient().when(subscriptions.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(customers.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsAnActiveSubscriptionStartingToday() {
        Subscription subscription = service.create(input(1L, null, 2, null));

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getCustomer()).isSameAs(ava);
        assertThat(subscription.getPlan()).isSameAs(coffee);
        assertThat(subscription.getQuantity()).isEqualTo(2);
        assertThat(subscription.getNextRunDate()).isEqualTo(TODAY);
        assertThat(subscription.getAnchorDate()).isEqualTo(TODAY);
    }

    @Test
    void createsTheCustomerInlineWithANormalisedEmail() {
        Subscription subscription = service.create(
                input(null, new NewCustomerInput(" Zoe Kim ", " Zoe.Kim@Example.COM "), 1, TODAY.plusDays(7)));

        assertThat(subscription.getCustomer().getName()).isEqualTo("Zoe Kim");
        assertThat(subscription.getCustomer().getEmail()).isEqualTo("zoe.kim@example.com");
        assertThat(subscription.getNextRunDate()).isEqualTo(TODAY.plusDays(7));
    }

    @Test
    void requiresExactlyOneWayToIdentifyTheCustomer() {
        assertThatThrownBy(() -> service.create(input(null, null, 1, null)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.create(input(1L, new NewCustomerInput("Zoe Kim", "zoe@example.com"), 1, null)))
                .isInstanceOf(BusinessRuleException.class);
        verify(subscriptions, never()).save(any());
    }

    @Test
    void rejectsADuplicateCustomerEmail() {
        when(customers.existsByEmail("ava.patel@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(input(null, new NewCustomerInput("Ava", "Ava.Patel@example.com"), 1, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejectsAStartDateInThePast() {
        assertThatThrownBy(() -> service.create(input(1L, null, 1, TODAY.minusDays(1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("past");
    }

    @Test
    void rejectsUnknownAndRetiredPlans() {
        assertThatThrownBy(() -> service.create(new CreateSubscriptionInput(1L, null, 99L, 1, Frequency.WEEKLY, null)))
                .isInstanceOf(NotFoundException.class);

        coffee.retire();
        assertThatThrownBy(() -> service.create(input(1L, null, 1, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no longer offered");
    }

    @Test
    void pauseResumeAndCancelLoadTheSubscriptionAndApplyTheTransition() {
        Subscription subscription = Entities.subscription(5, ava, coffee, 1, Frequency.WEEKLY, TODAY.minusWeeks(2));
        when(subscriptions.findWithDetailsById(5L)).thenReturn(Optional.of(subscription));

        assertThat(service.pause(5L).getStatus()).isEqualTo(SubscriptionStatus.PAUSED);

        Subscription resumed = service.resume(5L);
        assertThat(resumed.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(resumed.getNextRunDate()).isEqualTo(TODAY);

        assertThat(service.cancel(5L).getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void transitionsOnAnUnknownSubscriptionAreNotFound() {
        assertThatThrownBy(() -> service.pause(404L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.resume(404L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(404L)).isInstanceOf(NotFoundException.class);
    }

    private static CreateSubscriptionInput input(Long customerId, NewCustomerInput newCustomer, int quantity,
                                                 LocalDate startDate) {
        return new CreateSubscriptionInput(customerId, newCustomer, 1L, quantity, Frequency.WEEKLY, startDate);
    }
}
