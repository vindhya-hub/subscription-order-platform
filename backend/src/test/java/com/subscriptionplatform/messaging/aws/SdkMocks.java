package com.subscriptionplatform.messaging.aws;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDK client mocks that keep the interfaces' default methods: the {@code Consumer<Builder>} overloads and the
 * paginators build real request objects and call the request-object methods, which the tests stub (with
 * {@code doReturn}) and verify. Anything left unstubbed throws UnsupportedOperationException, so an unexpected
 * call fails the test.
 */
final class SdkMocks {

    private SdkMocks() {
    }

    static SqsClient sqs() {
        return mock(SqsClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }

    static SnsClient sns() {
        return mock(SnsClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }
}
