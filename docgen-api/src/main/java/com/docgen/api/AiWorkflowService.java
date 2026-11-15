package com.docgen.api;

import com.docgen.ArchitecturePlan;
import com.docgen.ArchitecturePlanJson;
import com.docgen.ArchitecturePlanningService;
import com.docgen.LLMProviderFactory;
import com.docgen.ProjectDocumentationService;
import com.docgen.ProviderSelection;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Coordinates AI operations for projects: validates the request, snapshots the
 * exact inputs and hands the local provider call to {@link JobService}.
 */
@Service
class AiWorkflowService {
    private final ProjectService projects;
    private final AttachmentService attachments;
    private final PlanRepository plans;
    private final ArtifactRepository artifacts;
    private final JobService jobs;
    private final ArchitecturePlanningService planning;
    private final ProjectDocumentationService documentation;
    private final JsonColumns json;
    private final Clock clock;

    AiWorkflowService(
            ProjectService projects,
            AttachmentService attachments,
            PlanRepository plans,
            ArtifactRepository artifacts,
            JobService jobs,
            ArchitecturePlanningService planning,
            ProjectDocumentationService documentation,
            JsonColumns json,
            Clock clock
    ) {
        this.projects = projects;
        this.attachments = attachments;
        this.plans = plans;
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.planning = planning;
        this.documentation = documentation;
        this.json = json;
        this.clock = clock;
    }

    private record Prepared(
            ProjectRepository.ProjectRecord project,
            ProviderSelection selection,
            ArchitecturePlanningService.Preparation planning,
            ProjectDocumentationService.Preparation documentation,
            String planId
    ) {
    }

    JobService.JobView startPlanning(String owner, String projectId, WorkflowDtos.PlanningRequest request) {
        Prepared prepared = preparePlanning(owner, projectId, request.provider(), request.model());
        String project = prepared.project().id();
        ProviderSelection selection = prepared.selection();
        ArchitecturePlanningService.Preparation input = prepared.planning();
        return jobs.submit(owner, project, JobRepository.Type.ARCHITECTURE_PLAN, selection.provider(),
                effectiveModel(selection), () -> {
                    ArchitecturePlanningService.Result result = planning.plan(input, selection);
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
        String project = prepared.project().id();
        String planId = prepared.planId();
        ProviderSelection selection = prepared.selection();
        ProjectDocumentationService.Preparation input = prepared.documentation();
        return jobs.submit(owner, project, JobRepository.Type.PROJECT_DOCUMENTATION, selection.provider(),
                effectiveModel(selection), () -> {
                    ProjectDocumentationService.Result result = documentation.generate(input, selection,
                            com.docgen.DocumentationService.ProgressListener.NONE);
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
        return new Prepared(project, selection, preparation, null, null);
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
        return new Prepared(project, selection, null, preparation, plan.id());
    }

    /** The request's model wins; otherwise the project's model; otherwise the default. */
    private static ProviderSelection selection(ProjectRepository.ProjectRecord project, String provider,
                                               String model) {
        if (provider != null && !provider.isBlank() && !ProviderSelection.PROVIDER.equals(provider)) {
            throw new InvalidRequestException();
        }
        String selectedModel = model == null || model.isBlank() ? project.model() : model;
        try {
            return new ProviderSelection(selectedModel);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidRequestException();
        }
    }

    static String effectiveModel(ProviderSelection selection) {
        return selection.model() != null ? selection.model() : LLMProviderFactory.DEFAULT_MODEL;
    }

    private static ArtifactRepository.ArtifactRecord artifact(String projectId, String owner, String planId,
                                                              ArtifactRepository.Kind kind, String content,
                                                              Instant now) {
        return new ArtifactRepository.ArtifactRecord(Ids.random(), projectId, owner, planId, kind, content, 1,
                false, now, now);
    }
}
