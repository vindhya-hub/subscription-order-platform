package com.subscriptionplatform.processing;

/** The payment or fulfilment step failed; the message should be retried (or dead-lettered). */
public class ProcessingFailedException extends RuntimeException {

    public ProcessingFailedException(String message) {
        super(message);
    }
}
