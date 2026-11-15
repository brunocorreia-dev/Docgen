package com.docgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * User-authored description of a project idea. Every field is untrusted input:
 * the constructor normalizes and bounds it so every interface (CLI, API, tests)
 * gets the same limits, independent of transport-level validation.
 */
public record ProjectBrief(
        String name,
        String description,
        List<String> functionalRequirements,
        List<String> nonFunctionalRequirements,
        List<String> preferredTechnologies,
        List<String> constraints,
        OutputLanguage outputLanguage
) {
    public static final int MAX_NAME = 120;
    public static final int MAX_DESCRIPTION = 8_000;
    public static final int MAX_REQUIREMENTS = 40;
    public static final int MAX_REQUIREMENT = 300;
    public static final int MAX_TECHNOLOGIES = 30;
    public static final int MAX_TECHNOLOGY = 60;
    public static final int MAX_CONSTRAINTS = 20;
    public static final int MAX_CONSTRAINT = 300;

    public ProjectBrief {
        name = UntrustedText.line(name, MAX_NAME);
        description = UntrustedText.block(description, MAX_DESCRIPTION);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A project name is required");
        }
        if (description.isEmpty()) {
            throw new IllegalArgumentException("A project description is required");
        }
        functionalRequirements = items(functionalRequirements, MAX_REQUIREMENTS, MAX_REQUIREMENT);
        nonFunctionalRequirements = items(nonFunctionalRequirements, MAX_REQUIREMENTS, MAX_REQUIREMENT);
        preferredTechnologies = items(preferredTechnologies, MAX_TECHNOLOGIES, MAX_TECHNOLOGY);
        constraints = items(constraints, MAX_CONSTRAINTS, MAX_CONSTRAINT);
        outputLanguage = Objects.requireNonNullElse(outputLanguage, OutputLanguage.PT_BR);
    }

    private static List<String> items(List<String> values, int maxItems, int maxCharacters) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > maxItems) {
            throw new IllegalArgumentException("Too many list items");
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            String item = UntrustedText.line(value, maxCharacters);
            if (!item.isEmpty() && !normalized.contains(item)) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    public enum OutputLanguage {
        PT_BR("pt-BR", "Brazilian Portuguese"),
        EN("en", "English");

        private final String tag;
        private final String promptName;

        OutputLanguage(String tag, String promptName) {
            this.tag = tag;
            this.promptName = promptName;
        }

        public String tag() {
            return tag;
        }

        String promptName() {
            return promptName;
        }

        public static OutputLanguage fromTag(String tag) {
            for (OutputLanguage language : values()) {
                if (language.tag.equalsIgnoreCase(tag)) {
                    return language;
                }
            }
            throw new IllegalArgumentException("Unsupported output language");
        }
    }
}
