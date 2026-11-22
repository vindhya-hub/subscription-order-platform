package com.subscriptionplatform.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A full application context (H2 + in-memory broker) in which nothing runs on its own: no queue consumer, no
 * scheduled jobs, and a {@link MutableClock}. Tests drive the billing run and {@code OrderQueueWorker.pollOnce}
 * themselves, step by step. Test classes using it share one cached context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "app.messaging.consumer.enabled=false",
        "app.scheduling.enabled=false",
        "app.messaging.retry-backoff=2s"
})
@ActiveProfiles("test")
@Import({TestData.class, TestClockConfiguration.class})
public @interface ManualPipelineTest {
}
