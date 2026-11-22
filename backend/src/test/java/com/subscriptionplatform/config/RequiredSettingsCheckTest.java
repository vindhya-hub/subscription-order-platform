package com.subscriptionplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subscriptionplatform.SubscriptionOrderPlatformApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

class RequiredSettingsCheckTest {

    @Test
    void theDefaultLocalSetupNeedsNothing() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.messaging.provider", "in-memory");

        assertThat(RequiredSettingsCheck.check(environment)).isEmpty();
    }

    @Test
    void thePostgresProfileNeedsTheDatasourceSettings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/subscriptions")
                .withProperty("spring.datasource.username", " ")
                // As in application-postgres.yml when the variable is not set.
                .withProperty("spring.datasource.password", "${SPRING_DATASOURCE_PASSWORD}");
        environment.setActiveProfiles("postgres");

        List<String> problems = RequiredSettingsCheck.check(environment);

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).startsWith("SPRING_DATASOURCE_USERNAME");
        assertThat(problems.get(1)).startsWith("SPRING_DATASOURCE_PASSWORD").contains("'postgres' profile");
    }

    @Test
    void theAwsProviderNeedsTheRegionAndEachResourceByArnUrlOrName() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.messaging.provider", "aws")
                .withProperty("app.messaging.aws.topic-arn", "arn:aws:sns:eu-west-1:123456789012:order-events")
                .withProperty("app.messaging.aws.queue-name", "order-processing");

        assertThat(RequiredSettingsCheck.check(environment)).satisfiesExactly(
                problem -> assertThat(problem).startsWith("AWS_REGION"),
                problem -> assertThat(problem).startsWith("SQS_ORDER_DLQ_URL or SQS_ORDER_DLQ_NAME"));
    }

    @Test
    void aCompleteAwsConfigurationPasses() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.messaging.provider", "AWS")
                .withProperty("app.messaging.aws.region", "eu-west-1")
                .withProperty("app.messaging.aws.topic-name", "order-events")
                .withProperty("app.messaging.aws.queue-url", "https://sqs.eu-west-1.amazonaws.com/123/order-processing")
                .withProperty("app.messaging.aws.dead-letter-queue-name", "order-processing-dlq");

        assertThat(RequiredSettingsCheck.check(environment)).isEmpty();
    }

    @Test
    void rejectsAnUnknownProvider() {
        MockEnvironment environment = new MockEnvironment().withProperty("app.messaging.provider", "kafka");

        assertThat(RequiredSettingsCheck.check(environment)).singleElement()
                .asString().contains("must be 'in-memory' or 'aws'");
    }

    @Test
    void theApplicationRefusesToStartWithoutItsRequiredSettings() {
        // Blank values given on the command line take precedence over anything set in the environment.
        SpringApplicationBuilder application = new SpringApplicationBuilder(SubscriptionOrderPlatformApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("postgres", "aws");

        assertThatThrownBy(() -> application.run(
                "--SPRING_DATASOURCE_URL=", "--SPRING_DATASOURCE_USERNAME=", "--SPRING_DATASOURCE_PASSWORD=",
                "--AWS_REGION=", "--SNS_ORDER_TOPIC_ARN=", "--SNS_ORDER_TOPIC_NAME=", "--SQS_ORDER_QUEUE_URL=",
                "--SQS_ORDER_QUEUE_NAME=", "--SQS_ORDER_DLQ_URL=", "--SQS_ORDER_DLQ_NAME="))
                .isInstanceOf(MissingRequiredSettingsException.class)
                .satisfies(failure -> assertThat(((MissingRequiredSettingsException) failure).problems()).hasSize(7));
    }
}
