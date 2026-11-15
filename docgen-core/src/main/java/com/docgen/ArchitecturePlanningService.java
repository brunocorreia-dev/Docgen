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
 * and {@link #plan} (one local provider call) so that interfaces can snapshot
 * the exact input before the call runs asynchronously.</p>
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

    public Result plan(Preparation preparation, ProviderSelection selection) throws Exception {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(selection, "selection");
        LLMProvider provider = providerResolver.create(selection.model());
        String output = provider.generateStructured(preparation.prompt());
        ArchitecturePlanParser.ParsedPlan parsed = parser.parse(output, preparation.brief());
        String mermaid = MermaidDiagram.fromPlan(parsed.plan());
        MermaidDiagram.validate(mermaid);
        return new Result(parsed.plan(), mermaid, parsed.warnings());
    }
}
