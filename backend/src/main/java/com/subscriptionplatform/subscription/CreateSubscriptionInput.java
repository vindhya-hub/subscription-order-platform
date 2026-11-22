package com.subscriptionplatform.subscription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Command for creating a subscription; bound directly from the GraphQL {@code CreateSubscriptionInput}.
 * Exactly one of {@code customerId} and {@code newCustomer} must be supplied.
 */
public record CreateSubscriptionInput(
        Long customerId,
        @Valid NewCustomerInput newCustomer,
        @NotNull Long planId,
        @NotNull @Min(1) @Max(Subscription.MAX_QUANTITY) Integer quantity,
        @NotNull Frequency frequency,
        LocalDate startDate) {

    public record NewCustomerInput(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 254) String email) {
    }
}
