package com.docgen.api;

import com.docgen.ArchitecturePlan;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** HTTP contracts for transmission previews, planning and documentation generation. */
final class WorkflowDtos {
    private WorkflowDtos() {
    }

    record TransmissionRequest(
            @NotNull RemoteConsentService.Operation operation,
            @Pattern(regexp = ProjectDtos.PROVIDER) String provider,
            @Size(max = 100) @Pattern(regexp = ProjectDtos.MODEL) String model,
            @Size(max = 36) String planId
    ) {
    }

    record TransmissionPreview(
            RemoteConsentService.Operation operation,
            String provider,
            String model,
            boolean remote,
            boolean remoteEnabled,
            String destination,
            List<String> includedData,
            int documentCount,
            boolean documentsTrimmed,
            int characterCount,
            String consentToken,
            Instant consentExpiresAt
    ) {
    }

    record PlanningRequest(
            @Pattern(regexp = ProjectDtos.PROVIDER) String provider,
            @Size(max = 100) @Pattern(regexp = ProjectDtos.MODEL) String model,
            @Size(max = 64) String consentToken
    ) {
    }

    record DocumentationRequest(
            @NotNull @Size(max = 36) String planId,
            @Pattern(regexp = ProjectDtos.PROVIDER) String provider,
            @Size(max = 100) @Pattern(regexp = ProjectDtos.MODEL) String model,
            @Size(max = 64) String consentToken
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
