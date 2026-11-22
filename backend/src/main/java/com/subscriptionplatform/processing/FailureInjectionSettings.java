package com.subscriptionplatform.processing;

/**
 * Current failure injection.
 *
 * @param planCode restrict failures to orders for this plan; {@code null} means every plan
 */
public record FailureInjectionSettings(FailureMode mode, String planCode) {
}
