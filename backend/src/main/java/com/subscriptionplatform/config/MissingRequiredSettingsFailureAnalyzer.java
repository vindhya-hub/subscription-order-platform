package com.subscriptionplatform.config;

import java.util.stream.Collectors;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a {@link MissingRequiredSettingsException} into a readable "APPLICATION FAILED TO START" report. */
public class MissingRequiredSettingsFailureAnalyzer extends AbstractFailureAnalyzer<MissingRequiredSettingsException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingRequiredSettingsException cause) {
        String description = "Required configuration is missing or invalid:\n"
                + cause.problems().stream().map(problem -> "    - " + problem).collect(Collectors.joining("\n"));
        String action = """
                Provide the variables listed above as environment variables. With docker compose they are read \
                from .env (create one with scripts/init-env.sh). See .env.example and the "Configuration & \
                secrets" section of the README for every setting.""";
        return new FailureAnalysis(description, action, cause);
    }
}
