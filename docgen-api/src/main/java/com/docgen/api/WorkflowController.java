package com.docgen.api;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Planning, documentation and artifact endpoints nested under a project. */
@RestController
class WorkflowController {
    private final AiWorkflowService workflow;
    private final PlanService plans;
    private final ArtifactService artifacts;

    WorkflowController(AiWorkflowService workflow, PlanService plans, ArtifactService artifacts) {
        this.workflow = workflow;
        this.plans = plans;
        this.artifacts = artifacts;
    }

    @PostMapping("/api/v1/projects/{projectId}/transmission-previews")
    WorkflowDtos.TransmissionPreview preview(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody WorkflowDtos.TransmissionRequest request
    ) {
        return workflow.preview(CurrentUser.username(), projectId, request);
    }

    @PostMapping("/api/v1/projects/{projectId}/plans")
    ResponseEntity<JobService.JobView> plan(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody WorkflowDtos.PlanningRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(workflow.startPlanning(CurrentUser.username(), projectId, request));
    }

    @GetMapping("/api/v1/projects/{projectId}/plans")
    WorkflowDtos.PlanList plans(@PathVariable("projectId") String projectId) {
        return plans.list(CurrentUser.username(), projectId);
    }

    @GetMapping("/api/v1/projects/{projectId}/plans/{planId}")
    WorkflowDtos.PlanView planDetail(
            @PathVariable("projectId") String projectId,
            @PathVariable("planId") String planId
    ) {
        return plans.get(CurrentUser.username(), projectId, planId);
    }

    @PostMapping("/api/v1/projects/{projectId}/plans/{planId}/approval")
    WorkflowDtos.PlanView approve(
            @PathVariable("projectId") String projectId,
            @PathVariable("planId") String planId
    ) {
        return plans.approve(CurrentUser.username(), projectId, planId);
    }

    @PostMapping("/api/v1/projects/{projectId}/documentation")
    ResponseEntity<JobService.JobView> document(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody WorkflowDtos.DocumentationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(workflow.startDocumentation(CurrentUser.username(), projectId, request));
    }

    @GetMapping("/api/v1/artifacts")
    ArtifactService.ArtifactList allArtifacts() {
        return artifacts.listForOwner(CurrentUser.username());
    }

    @GetMapping("/api/v1/projects/{projectId}/artifacts")
    ArtifactService.ArtifactList projectArtifacts(@PathVariable("projectId") String projectId) {
        return artifacts.listForProject(CurrentUser.username(), projectId);
    }

    @GetMapping("/api/v1/projects/{projectId}/artifacts/{artifactId}")
    ArtifactService.ArtifactView artifact(
            @PathVariable("projectId") String projectId,
            @PathVariable("artifactId") String artifactId
    ) {
        return artifacts.get(CurrentUser.username(), projectId, artifactId);
    }

    @PutMapping("/api/v1/projects/{projectId}/artifacts/{artifactId}")
    ArtifactService.ArtifactView updateArtifact(
            @PathVariable("projectId") String projectId,
            @PathVariable("artifactId") String artifactId,
            @Valid @RequestBody ArtifactService.ArtifactUpdate update
    ) {
        return artifacts.update(CurrentUser.username(), projectId, artifactId, update);
    }

    @GetMapping("/api/v1/projects/{projectId}/artifacts/{artifactId}/content")
    ResponseEntity<byte[]> download(
            @PathVariable("projectId") String projectId,
            @PathVariable("artifactId") String artifactId
    ) {
        return attachment(artifacts.download(CurrentUser.username(), projectId, artifactId));
    }

    @GetMapping("/api/v1/projects/{projectId}/export")
    ResponseEntity<byte[]> export(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "planId", required = false) String planId
    ) {
        return attachment(artifacts.export(CurrentUser.username(), projectId, planId));
    }

    private static ResponseEntity<byte[]> attachment(ArtifactService.Download download) {
        MediaType type = download.mediaType().startsWith("text/")
                ? new MediaType(MediaType.parseMediaType(download.mediaType()), StandardCharsets.UTF_8)
                : MediaType.parseMediaType(download.mediaType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(download.body());
    }
}
