package com.docgen.api;

import com.docgen.ArchitecturePlanJson;
import com.docgen.DocumentationArtifacts;
import com.docgen.DocumentationValidator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generated documents: review, edit (passive-Markdown validation and
 * optimistic revisions) and export. Exports use fixed file names only.
 */
@Service
class ArtifactService {
    static final int MAX_EDIT_CHARACTERS = 200_000;

    private final ArtifactRepository artifacts;
    private final ProjectService projects;
    private final PlanService plans;
    private final PlanRepository planRepository;
    private final Clock clock;

    ArtifactService(ArtifactRepository artifacts, ProjectService projects, PlanService plans,
                    PlanRepository planRepository, Clock clock) {
        this.artifacts = artifacts;
        this.projects = projects;
        this.plans = plans;
        this.planRepository = planRepository;
        this.clock = clock;
    }

    record ArtifactSummary(
            String id,
            String projectId,
            String projectName,
            String planId,
            ArtifactRepository.Kind kind,
            String fileName,
            String mediaType,
            boolean editable,
            int revision,
            boolean edited,
            int characters,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record ArtifactList(List<ArtifactSummary> artifacts) {
    }

    record ArtifactView(
            String id,
            String projectId,
            String planId,
            ArtifactRepository.Kind kind,
            String fileName,
            String mediaType,
            boolean editable,
            int revision,
            boolean edited,
            String origin,
            Instant createdAt,
            Instant updatedAt,
            String content
    ) {
    }

    record ArtifactUpdate(
            @NotNull @Size(max = MAX_EDIT_CHARACTERS) String content,
            @Min(1) int expectedRevision
    ) {
    }

    record Download(String fileName, String mediaType, byte[] body) {
    }

    ArtifactList listForProject(String owner, String projectId) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        return new ArtifactList(artifacts.listForProject(project.id(), owner).stream()
                .map(ArtifactService::summary).toList());
    }

    ArtifactList listForOwner(String owner) {
        return new ArtifactList(artifacts.listForOwner(owner, 200).stream().map(ArtifactService::summary).toList());
    }

    ArtifactView get(String owner, String projectId, String artifactId) {
        return view(require(owner, projectId, artifactId));
    }

    ArtifactView update(String owner, String projectId, String artifactId, ArtifactUpdate update) {
        ArtifactRepository.ArtifactRecord artifact = require(owner, projectId, artifactId);
        if (!artifact.kind().editable()) {
            throw new ConflictException("artifact_read_only", "Diagrams are generated from the plan and cannot be edited.");
        }
        String content;
        try {
            content = DocumentationValidator.redactAndValidate(update.content(), artifact.kind().fileName());
        } catch (IOException unsafe) {
            throw new UnprocessableContentException("unsafe_markdown",
                    "The document must be passive Markdown that starts with an H1 heading.");
        }
        if (!artifacts.updateContent(artifact.id(), artifact.projectId(), owner, update.expectedRevision(), content,
                clock.instant())) {
            throw new ConflictException("revision_conflict",
                    "The document was changed elsewhere. Reload it before saving.");
        }
        projects.touch(owner, artifact.projectId());
        return get(owner, projectId, artifactId);
    }

    Download download(String owner, String projectId, String artifactId) {
        ArtifactRepository.ArtifactRecord artifact = require(owner, projectId, artifactId);
        return new Download(artifact.kind().fileName(), artifact.kind().mediaType(),
                artifact.content().getBytes(StandardCharsets.UTF_8));
    }

    /** ZIP with the latest README, ARCHITECTURE, diagram and plan JSON generated for one plan. */
    Download export(String owner, String projectId, String planId) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        PlanRepository.PlanRecord plan = planId == null || planId.isBlank()
                ? latestDocumentedPlan(owner, project.id())
                : plans.require(owner, project.id(), planId);
        Map<ArtifactRepository.Kind, ArtifactRepository.ArtifactRecord> latest =
                new EnumMap<>(ArtifactRepository.Kind.class);
        for (ArtifactRepository.ArtifactRecord artifact : artifacts.forPlan(plan.id(), project.id(), owner)) {
            latest.putIfAbsent(artifact.kind(), artifact);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (ArtifactRepository.ArtifactRecord artifact : latest.values()) {
                entry(zip, artifact.kind().fileName(), artifact.content());
            }
            entry(zip, DocumentationArtifacts.PLAN, ArchitecturePlanJson.writePretty(PlanService.read(plan)));
            if (!latest.containsKey(ArtifactRepository.Kind.DIAGRAM)) {
                entry(zip, DocumentationArtifacts.DIAGRAM, plan.mermaid());
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("Export could not be assembled");
        }
        return new Download("docgen-" + slug(project.brief().name()) + ".zip", "application/zip",
                buffer.toByteArray());
    }

    private PlanRepository.PlanRecord latestDocumentedPlan(String owner, String projectId) {
        List<PlanRepository.PlanRecord> all = planRepository.list(projectId, owner);
        for (PlanRepository.PlanRecord plan : all) {
            if (!artifacts.forPlan(plan.id(), projectId, owner).isEmpty()) {
                return plan;
            }
        }
        return all.stream().findFirst().orElseThrow(() ->
                new ConflictException("nothing_to_export", "Generate an architecture plan before exporting."));
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String slug(String name) {
        String ascii = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "project" : slug;
    }

    private ArtifactRepository.ArtifactRecord require(String owner, String projectId, String artifactId) {
        ProjectRepository.ProjectRecord project = projects.require(owner, projectId);
        return artifacts.find(Ids.require(artifactId), project.id(), owner).orElseThrow(ResourceNotFoundException::new);
    }

    private static ArtifactSummary summary(ArtifactRepository.ArtifactMetadata metadata) {
        return new ArtifactSummary(metadata.id(), metadata.projectId(), metadata.projectName(), metadata.planId(),
                metadata.kind(), metadata.kind().fileName(), metadata.kind().mediaType(), metadata.kind().editable(),
                metadata.revision(), metadata.edited(), metadata.characters(), metadata.createdAt(),
                metadata.updatedAt());
    }

    private static ArtifactView view(ArtifactRepository.ArtifactRecord artifact) {
        return new ArtifactView(artifact.id(), artifact.projectId(), artifact.planId(), artifact.kind(),
                artifact.kind().fileName(), artifact.kind().mediaType(), artifact.kind().editable(),
                artifact.revision(), artifact.edited(), artifact.edited() ? "AI_GENERATED_HUMAN_EDITED" : "AI_GENERATED",
                artifact.createdAt(), artifact.updatedAt(), artifact.content());
    }
}
