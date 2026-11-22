package com.subscriptionplatform.config;

import java.util.List;

/** Thrown at startup by {@link RequiredSettingsCheck} when required configuration is missing or invalid. */
public class MissingRequiredSettingsException extends IllegalStateException {

    private final List<String> problems;

    public MissingRequiredSettingsException(List<String> problems) {
        super("Missing or invalid required configuration: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
