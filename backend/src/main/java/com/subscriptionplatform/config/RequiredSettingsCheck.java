package com.subscriptionplatform.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Fails fast when a setting the active configuration depends on is missing. It runs while the environment is
 * prepared, before any bean (or database / AWS connection) is created, and reports every problem at once:
 * <ul>
 *   <li>the {@code postgres} profile needs the datasource URL, username and password;</li>
 *   <li>{@code app.messaging.provider=aws} (the {@code aws} profile) needs the region plus the topic, queue and
 *       dead-letter queue, each by ARN/URL or by name.</li>
 * </ul>
 * Registered in {@code META-INF/spring.factories}; {@link MissingRequiredSettingsFailureAnalyzer} renders the
 * resulting error.
 */
public class RequiredSettingsCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final String POSTGRES = "the 'postgres' profile";
    private static final String AWS = "app.messaging.provider=aws (the 'aws' profile)";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        List<String> problems = check(event.getEnvironment());
        if (!problems.isEmpty()) {
            throw new MissingRequiredSettingsException(problems);
        }
    }

    /** Runs after logging is initialised so the failure is reported through the normal channels. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static List<String> check(Environment environment) {
        List<String> problems = new ArrayList<>();
        if (environment.acceptsProfiles(Profiles.of("postgres"))) {
            require(environment, problems, POSTGRES,
                    new Setting("SPRING_DATASOURCE_URL", "spring.datasource.url"));
            require(environment, problems, POSTGRES,
                    new Setting("SPRING_DATASOURCE_USERNAME", "spring.datasource.username"));
            require(environment, problems, POSTGRES,
                    new Setting("SPRING_DATASOURCE_PASSWORD", "spring.datasource.password"));
        }

        String provider = value(environment, "app.messaging.provider");
        if (provider == null || provider.equalsIgnoreCase("in-memory")) {
            return problems;
        }
        if (!provider.equalsIgnoreCase("aws")) {
            problems.add("MESSAGING_PROVIDER (app.messaging.provider) must be 'in-memory' or 'aws', not '"
                    + provider + "'");
            return problems;
        }
        require(environment, problems, AWS, new Setting("AWS_REGION", "app.messaging.aws.region"));
        require(environment, problems, AWS,
                new Setting("SNS_ORDER_TOPIC_ARN", "app.messaging.aws.topic-arn"),
                new Setting("SNS_ORDER_TOPIC_NAME", "app.messaging.aws.topic-name"));
        require(environment, problems, AWS,
                new Setting("SQS_ORDER_QUEUE_URL", "app.messaging.aws.queue-url"),
                new Setting("SQS_ORDER_QUEUE_NAME", "app.messaging.aws.queue-name"));
        require(environment, problems, AWS,
                new Setting("SQS_ORDER_DLQ_URL", "app.messaging.aws.dead-letter-queue-url"),
                new Setting("SQS_ORDER_DLQ_NAME", "app.messaging.aws.dead-letter-queue-name"));
        return problems;
    }

    /** Records a problem unless at least one of the alternatives has a value. */
    private static void require(Environment environment, List<String> problems, String requiredBy,
                                Setting... alternatives) {
        if (Arrays.stream(alternatives).anyMatch(setting -> value(environment, setting.property()) != null)) {
            return;
        }
        String variables = String.join(" or ", Arrays.stream(alternatives).map(Setting::variable).toList());
        String properties = String.join(" / ", Arrays.stream(alternatives).map(Setting::property).toList());
        problems.add(variables + " (" + properties + ") is required by " + requiredBy);
    }

    /** The property's value, or {@code null} when it is unset, blank or an unresolvable {@code ${...}}. */
    private static String value(Environment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return StringUtils.hasText(value) ? value.strip() : null;
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return null;
        }
    }

    private record Setting(String variable, String property) {
    }
}
