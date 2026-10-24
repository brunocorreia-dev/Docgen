package com.docgen.api;

import com.docgen.ArchitecturePlan;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** HTTP contracts for planning and documentation generation. */
final class WorkflowDtos {
    private WorkflowDtos() {
    }

    record PlanningRequest(
            @Pattern(regexp = ProjectDtos.PROVIDER) String provider,
            @Size(max = 100) @Pattern(regexp = ProjectDtos.MODEL) String model
    ) {
    }

    record DocumentationRequest(
            @NotNull @Size(max = 36) String planId,
            @Pattern(regexp = ProjectDtos.PROVIDER) String provider,
            @Size(max = 100) @Pattern(regexp = ProjectDtos.MODEL) String model
    ) {
    }

    /** Every plan is an AI proposal; {@code requiresHumanReview} is always true. */
    record PlanView(
            String id,
            String projectId,
            PlanRepository.Status status,
            String provider,
            String model,
            String origin,
            boolean requiresHumanReview,
            Instant createdAt,
            Instant approvedAt,
            ArchitecturePlan plan,
            String mermaid,
            List<String> warnings
    ) {
    }

    record PlanSummary(
            String id,
            PlanRepository.Status status,
            String provider,
            String model,
            Instant createdAt,
            Instant approvedAt,
            String summary,
            int componentCount
    ) {
    }

    record PlanList(List<PlanSummary> plans) {
    }
}
