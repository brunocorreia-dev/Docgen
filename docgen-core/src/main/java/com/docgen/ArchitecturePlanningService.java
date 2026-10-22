package com.docgen;

import java.util.List;
import java.util.Objects;

/**
 * Plans the architecture of an idea-based project. It is independent from the
 * repository documentation pipeline: its input is a {@link ProjectBrief} plus
 * text extracted from attachments, and its output is a validated, structured
 * {@link ArchitecturePlan} with a Mermaid export.
 *
 * <p>Work is split into {@link #prepare} (builds the exact prompt, no network)
 * and {@link #plan} (one provider call) so that interfaces can show the user
 * what would be transmitted and bind their consent to that exact content.</p>
 */
public final class ArchitecturePlanningService {
    private final LLMProviderResolver providerResolver;
    private final ProjectPromptBuilder promptBuilder;
    private final ArchitecturePlanParser parser;

    public ArchitecturePlanningService() {
        this(LLMProviderFactory::create);
    }

    public ArchitecturePlanningService(LLMProviderResolver providerResolver) {
        this.providerResolver = Objects.requireNonNull(providerResolver, "providerResolver");
        this.promptBuilder = new ProjectPromptBuilder();
        this.parser = new ArchitecturePlanParser();
    }

    public record Preparation(ProjectBrief brief, String prompt, int documentCount, boolean documentsTrimmed) {
        public Preparation {
            Objects.requireNonNull(brief, "brief");
            Objects.requireNonNull(prompt, "prompt");
        }

        public int characterCount() {
            return prompt.length();
        }

        /** Binds consent to this exact prompt and the selected provider/model. */
        public String digest(ProviderSelection selection) {
            return PromptDigest.of("plan", selection.provider(), String.valueOf(selection.model()), prompt);
        }
    }

    public record Result(ArchitecturePlan plan, String mermaid, List<String> warnings) {
        public Result {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(mermaid, "mermaid");
            warnings = List.copyOf(warnings);
        }
    }

    public Preparation prepare(ProjectBrief brief, List<SupportingDocument> documents) {
        Objects.requireNonNull(brief, "brief");
        ProjectPromptBuilder.Prompt prompt = promptBuilder.buildPlanningPrompt(
                brief, documents == null ? List.of() : documents);
        return new Preparation(brief, prompt.text(), prompt.documentCount(), prompt.documentsTrimmed());
    }

    public Result plan(
            Preparation preparation,
            ProviderSelection selection,
            DocumentationService.RemoteConsent remoteConsent
    ) throws Exception {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(remoteConsent, "remoteConsent");
        LLMProvider provider = RemoteGate.authorize(providerResolver, selection, remoteConsent,
                preparation.documentCount(), preparation.characterCount());
        String output = provider.generateStructured(preparation.prompt());
        ArchitecturePlanParser.ParsedPlan parsed = parser.parse(output, preparation.brief());
        String mermaid = MermaidDiagram.fromPlan(parsed.plan());
        MermaidDiagram.validate(mermaid);
        return new Result(parsed.plan(), mermaid, parsed.warnings());
    }
}
