package com.docgen;

import java.util.List;
import java.util.Objects;

/**
 * Validated, structured architecture proposal. Every value is either copied
 * verbatim from the user's brief ({@link Source#USER}) or is a model suggestion
 * that requires human review; nothing in a plan describes implemented software.
 * Instances are produced only by {@link ArchitecturePlanParser}, which enforces
 * the limits, identifiers and cross-references documented there.
 */
public record ArchitecturePlan(
        String summary,
        List<String> objectives,
        List<Requirement> functionalRequirements,
        List<Requirement> nonFunctionalRequirements,
        ArchitectureStyle architecture,
        List<Component> components,
        List<Relationship> relationships,
        List<String> dataFlow,
        List<Technology> technologies,
        List<Integration> integrations,
        List<String> securityConsiderations,
        Persistence persistence,
        List<Phase> implementationPlan,
        List<Risk> risks,
        List<Decision> decisions,
        List<String> assumptions,
        List<String> openQuestions
) {
    public ArchitecturePlan {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(architecture, "architecture");
        Objects.requireNonNull(persistence, "persistence");
        objectives = List.copyOf(objectives);
        functionalRequirements = List.copyOf(functionalRequirements);
        nonFunctionalRequirements = List.copyOf(nonFunctionalRequirements);
        components = List.copyOf(components);
        relationships = List.copyOf(relationships);
        dataFlow = List.copyOf(dataFlow);
        technologies = List.copyOf(technologies);
        integrations = List.copyOf(integrations);
        securityConsiderations = List.copyOf(securityConsiderations);
        implementationPlan = List.copyOf(implementationPlan);
        risks = List.copyOf(risks);
        decisions = List.copyOf(decisions);
        assumptions = List.copyOf(assumptions);
        openQuestions = List.copyOf(openQuestions);
    }

    /** Where a requirement came from. Only USER is a fact supplied by a person. */
    public enum Source {
        USER,
        DOCUMENT_INFERENCE,
        AI_SUGGESTION
    }

    public enum ComponentType {
        CLIENT,
        SERVICE,
        API_GATEWAY,
        WORKER,
        DATABASE,
        CACHE,
        QUEUE,
        STORAGE,
        EXTERNAL,
        OTHER
    }

    public enum Impact {
        LOW,
        MEDIUM,
        HIGH
    }

    public record Requirement(String id, String description, Source source) {
    }

    public record ArchitectureStyle(String style, String rationale) {
    }

    public record Component(
            String id,
            String name,
            ComponentType type,
            String responsibility,
            List<String> technologies
    ) {
        public Component {
            technologies = List.copyOf(technologies);
        }
    }

    public record Relationship(String from, String to, String description) {
    }

    public record Technology(String name, String category, String justification) {
    }

    public record Integration(String name, String purpose) {
    }

    public record Persistence(String strategy, List<String> details) {
        public Persistence {
            details = List.copyOf(details);
        }
    }

    public record Phase(int order, String title, String goal, List<String> tasks, List<String> deliverables) {
        public Phase {
            tasks = List.copyOf(tasks);
            deliverables = List.copyOf(deliverables);
        }
    }

    public record Risk(String description, Impact impact, String mitigation) {
    }

    public record Decision(String title, String decision, String rationale, List<String> alternatives) {
        public Decision {
            alternatives = List.copyOf(alternatives);
        }
    }
}
