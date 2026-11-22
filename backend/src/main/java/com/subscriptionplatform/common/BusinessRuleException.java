package com.subscriptionplatform.common;

/** A request is well-formed but violates a domain rule. Surfaces as a BAD_REQUEST GraphQL error. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
