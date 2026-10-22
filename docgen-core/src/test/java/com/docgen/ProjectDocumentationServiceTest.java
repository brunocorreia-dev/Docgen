package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDocumentationServiceTest {
    private static ArchitecturePlan plan() throws Exception {
        return new ArchitecturePlanParser().parse(PlanFixtures.validPlanJson(), PlanFixtures.brief()).plan();
    }

    @Test
    void generatesValidatedReadmeArchitectureAndDiagramFromAnApprovedPlan() throws Exception {
        ArchitecturePlanningServiceTest.FakeProvider provider = new ArchitecturePlanningServiceTest.FakeProvider(
                false, "# Inventory API\n\nProjeto em planejamento.", "# Arquitetura\n\nProposta.");
        ProjectDocumentationService service = new ProjectDocumentationService((name, model, allowRemote) -> provider);
        List<DocumentationService.Artifact> progress = new ArrayList<>();

        ProjectDocumentationService.Result result = service.generate(
                service.prepare(PlanFixtures.brief(), plan()),
                new ProviderSelection("ollama", null, false),
                transmission -> true,
                new DocumentationService.ProgressListener() {
                    @Override
                    public void generationStarted(DocumentationService.Artifact artifact) {
                        progress.add(artifact);
                    }
                });

        assertEquals("# Inventory API\n\nProjeto em planejamento.\n", result.readme());
        assertTrue(result.architecture().startsWith("# Arquitetura"));
        assertTrue(result.mermaid().contains("n2 -->|\"JDBC\"| n3"));
        assertEquals(List.of(DocumentationService.Artifact.README, DocumentationService.Artifact.ARCHITECTURE), progress);
        assertEquals(2, provider.documentationCalls);
        assertTrue(provider.prompts.getFirst().contains("<approved_plan>"));
    }

    @Test
    void activeMarkdownFromTheModelIsRejected() throws Exception {
        ProjectDocumentationService service = new ProjectDocumentationService((name, model, allowRemote) ->
                new ArchitecturePlanningServiceTest.FakeProvider(false, "# Hi\n\n<script>alert(1)</script>"));

        assertThrows(UnsafeOutputException.class, () -> service.generate(service.prepare(PlanFixtures.brief(), plan()),
                new ProviderSelection("ollama", null, false), transmission -> true,
                DocumentationService.ProgressListener.NONE));
    }

    @Test
    void secretsInModelOutputAreRedacted() throws Exception {
        ProjectDocumentationService service = new ProjectDocumentationService((name, model, allowRemote) ->
                new ArchitecturePlanningServiceTest.FakeProvider(false,
                        "# Hi\n\nkey gsk_abcdefghijklmnopqrstuvwxyz123456"));

        ProjectDocumentationService.Result result = service.generate(service.prepare(PlanFixtures.brief(), plan()),
                new ProviderSelection("ollama", null, false), transmission -> true,
                DocumentationService.ProgressListener.NONE);

        assertFalse(result.readme().contains("gsk_abcdefghijklmnopqrstuvwxyz123456"));
    }

    @Test
    void remoteDocumentationGenerationNeedsConsent() throws Exception {
        ArchitecturePlanningServiceTest.FakeProvider provider = new ArchitecturePlanningServiceTest.FakeProvider(
                true, "# Hi");
        ProjectDocumentationService service = new ProjectDocumentationService((name, model, allowRemote) -> provider);

        assertThrows(DocumentationService.RemoteConsentDeniedException.class, () -> service.generate(
                service.prepare(PlanFixtures.brief(), plan()), new ProviderSelection("groq", null, true),
                transmission -> false, DocumentationService.ProgressListener.NONE));
        assertEquals(0, provider.documentationCalls);
    }
}
