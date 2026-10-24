package com.docgen;

import java.util.Objects;

/**
 * Generates README.md and ARCHITECTURE.md for an idea-based project from its
 * brief and a human-approved {@link ArchitecturePlan}. Output passes the same
 * {@link DocumentationValidator} as repository documentation, and the diagram
 * is produced separately by {@link MermaidDiagram} so passive-Markdown rules
 * stay unchanged.
 */
public final class ProjectDocumentationService {
    private final LLMProviderResolver providerResolver;
    private final ProjectPromptBuilder promptBuilder = new ProjectPromptBuilder();

    public ProjectDocumentationService() {
        this(LLMProviderFactory::create);
    }

    public ProjectDocumentationService(LLMProviderResolver providerResolver) {
        this.providerResolver = Objects.requireNonNull(providerResolver, "providerResolver");
    }

    public record Preparation(ProjectBrief brief, ArchitecturePlan plan, String readmePrompt, String architecturePrompt) {
        public Preparation {
            Objects.requireNonNull(brief, "brief");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(readmePrompt, "readmePrompt");
            Objects.requireNonNull(architecturePrompt, "architecturePrompt");
        }

        public int characterCount() {
            return readmePrompt.length() + architecturePrompt.length();
        }
    }

    public record Result(String readme, String architecture, String mermaid) {
        public Result {
            Objects.requireNonNull(readme, "readme");
            Objects.requireNonNull(architecture, "architecture");
            Objects.requireNonNull(mermaid, "mermaid");
        }
    }

    public Preparation prepare(ProjectBrief brief, ArchitecturePlan plan) {
        return new Preparation(brief, plan,
                promptBuilder.buildReadmePrompt(brief, plan),
                promptBuilder.buildArchitecturePrompt(brief, plan));
    }

    public Result generate(
            Preparation preparation,
            ProviderSelection selection,
            DocumentationService.ProgressListener progressListener
    ) throws Exception {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(progressListener, "progressListener");
        LLMProvider provider = providerResolver.create(selection.model());
        progressListener.generationStarted(DocumentationService.Artifact.README);
        String readme = validated(provider.generateDocumentation(preparation.readmePrompt()),
                DocumentationArtifacts.README);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Documentation generation was cancelled.");
        }
        progressListener.generationStarted(DocumentationService.Artifact.ARCHITECTURE);
        String architecture = validated(provider.generateDocumentation(preparation.architecturePrompt()),
                DocumentationArtifacts.ARCHITECTURE);
        String mermaid = MermaidDiagram.fromPlan(preparation.plan());
        MermaidDiagram.validate(mermaid);
        return new Result(readme, architecture, mermaid);
    }

    private static String validated(String output, String artifact) throws UnsafeOutputException {
        try {
            return DocumentationValidator.redactAndValidate(output, artifact);
        } catch (java.io.IOException rejected) {
            throw new UnsafeOutputException(rejected.getMessage(), rejected);
        }
    }
}
