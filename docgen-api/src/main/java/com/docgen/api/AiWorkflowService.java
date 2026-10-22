package com.docgen.api;

import com.docgen.ArchitecturePlan;
import com.docgen.ArchitecturePlanJson;
import com.docgen.ArchitecturePlanningService;
import com.docgen.LLMProviderFactory;
import com.docgen.LLMProviderResolver;
import com.docgen.ProjectDocumentationService;
import com.docgen.ProviderSelection;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates AI operations for projects: shows what would be transmitted,
 * verifies operation-specific consent for remote providers, snapshots the
 * exact inputs and hands the provider call to {@link JobService}.
 */
@Service
class AiWorkflowService {
    private final ProjectService projects;
    private final AttachmentService attachments;
    private final PlanRepository plans;
    private final ArtifactRepository artifacts;
    private final JobService jobs;
    private final RemoteConsentService consents;
    private final ArchitecturePlanningService planning;
    private final ProjectDocumentationService documentation;
    private final LLMProviderResolver resolver;
    private final JsonColumns json;
    private final Clock clock;
    private final boolean remoteEnabled;

    AiWorkflowService(
            ProjectService projects,
            AttachmentService attachments,
            PlanRepository plans,
            ArtifactRepository artifacts,
            JobService jobs,
            RemoteConsentService consents,
            ArchitecturePlanningService planning,
            ProjectDocumentationService documentation,
            LLMProviderResolver resolver,
            JsonColumns json,
            Clock clock,
            DocGenApiProperties properties
    ) {
        this.projects = projects;
        this.attachments = attachments;
        this.plans = plans;
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.consents = consents;
        this.planning = planning;
        this.documentation = documentation;
        this.resolver = resolver;
        this.json = json;
        this.clock = clock;
        this.remoteEnabled = properties.remote().enabled();
    }

    private record Prepared(
            ProjectRepository.ProjectRecord project,
            ProviderSelection selection,
            boolean remote,
            String digest,
            int documentCount,
            boolean documentsTrimmed,
            int characterCount,
            List<String> includedData,
            ArchitecturePlanningService.Preparation planning,
            ProjectDocumentationService.Preparation documentation,
            String planId
    ) {
    }

    WorkflowDtos.TransmissionPreview preview(String owner, String projectId, WorkflowDtos.TransmissionRequest request) {
        Prepared prepared = request.operation() == RemoteConsentService.Operation.PLAN
                ? preparePlanning(owner, projectId, request.provider(), request.model())
                : prepareDocumentation(owner, projectId, request.planId(), request.provider(), request.model());
        RemoteConsentService.IssuedConsent consent = prepared.remote() && remoteEnabled
                ? consents.issue(owner, prepared.project().id(), request.operation(),
                prepared.selection().provider(), prepared.selection().model(), prepared.digest())
                : null;
        return new WorkflowDtos.TransmissionPreview(request.operation(), prepared.selection().provider(),
                effectiveModel(prepared.selection()), prepared.remote(), remoteEnabled,
                ProviderConfig.destination(resolver, prepared.selection().provider(), prepared.selection().model()),
                prepared.includedData(), prepared.documentCount(), prepared.documentsTrimmed(),
                prepared.characterCount(), consent == null ? null : consent.token(),
                consent == null ? null : consent.expiresAt());
    }

    JobService.JobView startPlanning(String owner, String projectId, WorkflowDtos.PlanningRequest request) {
        Prepared prepared = preparePlanning(owner, projectId, request.provider(), request.model());
        authorize(owner, prepared, RemoteConsentService.Operation.PLAN, request.consentToken());
        String project = prepared.project().id();
        ProviderSelection selection = prepared.selection();
        ArchitecturePlanningService.Preparation input = prepared.planning();
        return jobs.submit(owner, project, JobRepository.Type.ARCHITECTURE_PLAN, selection.provider(),
                effectiveModel(selection), () -> {
                    ArchitecturePlanningService.Result result = planning.plan(input, selection, transmission -> true);
                    String planId = Ids.random();
                    Instant now = clock.instant();
                    return new JobService.Outcome(planId, () -> {
                        plans.insert(new PlanRepository.PlanRecord(planId, project, owner,
                                PlanRepository.Status.DRAFT, selection.provider(), effectiveModel(selection),
                                ArchitecturePlanJson.write(result.plan()), result.mermaid(),
                                json.write(result.warnings()), now, null));
                        projects.touch(owner, project);
                    });
                });
    }

    JobService.JobView startDocumentation(String owner, String projectId, WorkflowDtos.DocumentationRequest request) {
        Prepared prepared = prepareDocumentation(owner, projectId, request.planId(), request.provider(),
                request.model());
        authorize(owner, prepared, RemoteConsentService.Operation.DOCUMENTATION, request.consentToken());
        String project = prepared.project().id();
        String planId = prepared.planId();
        ProviderSelection selection = prepared.selection();
        ProjectDocumentationService.Preparation input = prepared.documentation();
        return jobs.submit(owner, project, JobRepository.Type.PROJECT_DOCUMENTATION, selection.provider(),
                effectiveModel(selection), () -> {
                    ProjectDocumentationService.Result result = documentation.generate(input, selection,
                            transmission -> true, com.docgen.DocumentationService.ProgressListener.NONE);
                    Instant now = clock.instant();
                    List<ArtifactRepository.ArtifactRecord> created = List.of(
                            artifact(project, owner, planId, ArtifactRepository.Kind.README, result.readme(), now),
                            artifact(project, owner, planId, ArtifactRepository.Kind.ARCHITECTURE,
                                    result.architecture(), now),
                            artifact(project, owner, planId, ArtifactRepository.Kind.DIAGRAM, result.mermaid(), now));
                    return new JobService.Outcome(created.getFirst().id(), () -> {
                        created.forEach(artifacts::insert);
                        projects.touch(owner, project);
                    });
                });
    }

    private Prepared preparePlanning(String owner, String projectId, String provider, String model) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        ProviderSelection selection = selection(project, provider, model);
        ArchitecturePlanningService.Preparation preparation = planning.prepare(project.brief(),
                attachments.documents(owner, project.id()));
        List<String> included = new ArrayList<>(List.of("project_brief"));
        if (preparation.documentCount() > 0) {
            included.add("attachment_text");
        }
        return new Prepared(project, selection, isRemote(selection), preparation.digest(selection),
                preparation.documentCount(), preparation.documentsTrimmed(), preparation.characterCount(),
                included, preparation, null, null);
    }

    private Prepared prepareDocumentation(String owner, String projectId, String planId, String provider,
                                          String model) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        PlanRepository.PlanRecord plan = plans.find(Ids.require(planId), project.id(), owner)
                .orElseThrow(ResourceNotFoundException::new);
        if (plan.status() != PlanRepository.Status.APPROVED) {
            throw new ConflictException("plan_not_approved",
                    "Review and approve the architecture plan before generating documentation.");
        }
        ArchitecturePlan structured;
        try {
            structured = ArchitecturePlanJson.read(plan.planJson());
        } catch (IOException corrupted) {
            throw new IllegalStateException("Stored plan is unreadable");
        }
        ProviderSelection selection = selection(project, provider, model);
        ProjectDocumentationService.Preparation preparation = documentation.prepare(project.brief(), structured);
        return new Prepared(project, selection, isRemote(selection), preparation.digest(selection), 0, false,
                preparation.characterCount(), List.of("project_brief", "approved_plan"), null, preparation,
                plan.id());
    }

    private void authorize(String owner, Prepared prepared, RemoteConsentService.Operation operation, String token) {
        if (!prepared.remote()) {
            return;
        }
        if (!remoteEnabled) {
            throw new ConflictException("remote_disabled",
                    "Remote providers are disabled on this server (DOCGEN_ALLOW_REMOTE).");
        }
        consents.consume(token, owner, prepared.project().id(), operation, prepared.selection().provider(),
                prepared.selection().model(), prepared.digest());
    }

    private ProviderSelection selection(ProjectRepository.ProjectRecord project, String provider, String model) {
        String selectedProvider = provider == null || provider.isBlank() ? project.provider() : provider;
        String selectedModel = provider == null || provider.isBlank()
                ? (model == null || model.isBlank() ? project.model() : model)
                : model;
        try {
            return new ProviderSelection(selectedProvider, selectedModel, remoteEnabled);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidRequestException();
        }
    }

    private static boolean isRemote(ProviderSelection selection) {
        return LLMProviderFactory.supportedProviders().stream()
                .anyMatch(item -> item.id().equals(selection.provider()) && item.remote());
    }

    static String effectiveModel(ProviderSelection selection) {
        if (selection.model() != null) {
            return selection.model();
        }
        return LLMProviderFactory.supportedProviders().stream()
                .filter(item -> item.id().equals(selection.provider()))
                .map(LLMProviderFactory.ProviderDescriptor::defaultModel)
                .findFirst()
                .orElse(null);
    }

    private static ArtifactRepository.ArtifactRecord artifact(String projectId, String owner, String planId,
                                                              ArtifactRepository.Kind kind, String content,
                                                              Instant now) {
        return new ArtifactRepository.ArtifactRecord(Ids.random(), projectId, owner, planId, kind, content, 1,
                false, now, now);
    }
}
