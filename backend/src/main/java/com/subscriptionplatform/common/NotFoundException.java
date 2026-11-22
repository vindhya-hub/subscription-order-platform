package com.subscriptionplatform.common;

/** A referenced aggregate does not exist. Surfaces as a NOT_FOUND GraphQL error. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String entity, Object id) {
        super(entity + " " + id + " not found");
    }
}
