package com.docgen.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** HTTP contracts for projects. Mapped to/from {@link com.docgen.ProjectBrief} by {@link ProjectService}. */
final class ProjectDtos {
    static final String PROVIDER = "groq|ollama";
    static final String MODEL = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}";
    static final String LANGUAGE = "pt-BR|en";

    private ProjectDtos() {
    }

    record ProjectRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 8000) String description,
            @Size(max = 40) List<@NotBlank @Size(max = 300) String> functionalRequirements,
            @Size(max = 40) List<@NotBlank @Size(max = 300) String> nonFunctionalRequirements,
            @Size(max = 30) List<@NotBlank @Size(max = 60) String> preferredTechnologies,
            @Size(max = 20) List<@NotBlank @Size(max = 300) String> constraints,
            @NotBlank @Pattern(regexp = PROVIDER) String provider,
            @Size(max = 100) @Pattern(regexp = MODEL) String model,
            @Pattern(regexp = LANGUAGE) String outputLanguage
    ) {
    }

    record ProjectStatsView(int attachments, int plans, int approvedPlans, int artifacts, int activeJobs) {
    }

    record ProjectView(
            String id,
            String name,
            String description,
            List<String> functionalRequirements,
            List<String> nonFunctionalRequirements,
            List<String> preferredTechnologies,
            List<String> constraints,
            String provider,
            String model,
            String outputLanguage,
            Instant createdAt,
            Instant updatedAt,
            ProjectStatsView stats
    ) {
    }

    record ProjectSummary(
            String id,
            String name,
            String descriptionPreview,
            String provider,
            String model,
            Instant createdAt,
            Instant updatedAt,
            ProjectStatsView stats
    ) {
    }

    record ProjectList(List<ProjectSummary> projects) {
    }
}
