package com.docgen;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns untrusted model output into a validated {@link ArchitecturePlan}.
 *
 * <p>Model output is treated as hostile data: the JSON is size- and
 * depth-bounded, every string is normalized, bounded and secret-redacted,
 * enumerations fall back to safe defaults, component identifiers are replaced
 * by a restricted slug, relationships that reference unknown components are
 * dropped, and list sizes are capped. Requirements the user wrote are copied
 * from the brief verbatim and labelled {@link ArchitecturePlan.Source#USER};
 * the model can never mark its own text as user-provided.</p>
 */
public final class ArchitecturePlanParser {
    static final int MAX_OUTPUT_CHARACTERS = 256 * 1024;
    static final int MAX_SHORT = 120;
    static final int MAX_MEDIUM = 600;
    static final int MAX_LONG = 2_000;
    static final int MAX_COMPONENTS = 30;
    static final int MAX_RELATIONSHIPS = 60;
    static final int MAX_AI_REQUIREMENTS = 40;
    static final int MAX_LIST = 20;
    static final int MAX_PHASES = 12;
    static final int MAX_PHASE_ITEMS = 15;
    static final int MAX_COMPONENT_ID = 40;

    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(16)
                    .maxStringLength(64 * 1024)
                    .maxNumberLength(32)
                    .build())
            .build());

    public record ParsedPlan(ArchitecturePlan plan, List<String> warnings) {
        public ParsedPlan {
            warnings = List.copyOf(warnings);
        }
    }

    /** Raised when model output cannot be turned into a usable plan. */
    public static final class InvalidPlanException extends IOException {
        public InvalidPlanException(String message) {
            super(message);
        }
    }

    public ParsedPlan parse(String modelOutput, ProjectBrief brief) throws InvalidPlanException {
        if (brief == null) {
            throw new IllegalArgumentException("brief is required");
        }
        JsonNode root = readObject(modelOutput);
        List<String> warnings = new ArrayList<>();

        String summary = text(root, "summary", MAX_LONG, true);
        if (summary.isEmpty()) {
            throw new InvalidPlanException("The model output did not contain a project summary.");
        }
        JsonNode architectureNode = root.path("architecture");
        ArchitecturePlan.ArchitectureStyle architecture = new ArchitecturePlan.ArchitectureStyle(
                orDefault(text(architectureNode, "style", MAX_SHORT, false), "Not specified"),
                text(architectureNode, "rationale", MAX_LONG, true));

        ComponentSet componentSet = components(root.path("components"), warnings);
        if (componentSet.components().isEmpty()) {
            throw new InvalidPlanException("The model output did not describe any architecture component.");
        }

        JsonNode persistenceNode = root.path("persistence");
        ArchitecturePlan.Persistence persistence = new ArchitecturePlan.Persistence(
                orDefault(text(persistenceNode, "strategy", MAX_MEDIUM, true), "Not specified"),
                strings(persistenceNode.path("details"), MAX_LIST, MAX_MEDIUM, "persistence.details", warnings));

        ArchitecturePlan plan = new ArchitecturePlan(
                summary,
                strings(root.path("objectives"), MAX_LIST, MAX_MEDIUM, "objectives", warnings),
                requirements(root.path("functionalRequirements"), brief.functionalRequirements(), "FR", warnings),
                requirements(root.path("nonFunctionalRequirements"), brief.nonFunctionalRequirements(), "NFR",
                        warnings),
                architecture,
                componentSet.components(),
                relationships(root.path("relationships"), componentSet, warnings),
                strings(root.path("dataFlow"), MAX_LIST, MAX_MEDIUM, "dataFlow", warnings),
                objects(root.path("technologies"), MAX_LIST * 3 / 2, "technologies", warnings, node -> {
                    String name = text(node, "name", MAX_SHORT, false);
                    return name.isEmpty() ? null : new ArchitecturePlan.Technology(name,
                            orDefault(text(node, "category", MAX_SHORT, false), "General"),
                            text(node, "justification", MAX_MEDIUM, true));
                }),
                objects(root.path("integrations"), MAX_LIST, "integrations", warnings, node -> {
                    String name = text(node, "name", MAX_SHORT, false);
                    return name.isEmpty() ? null : new ArchitecturePlan.Integration(name,
                            text(node, "purpose", MAX_MEDIUM, true));
                }),
                strings(root.path("securityConsiderations"), MAX_LIST + 5, MAX_MEDIUM, "securityConsiderations",
                        warnings),
                persistence,
                phases(root.path("implementationPlan"), warnings),
                objects(root.path("risks"), MAX_LIST, "risks", warnings, node -> {
                    String description = text(node, "description", MAX_MEDIUM, true);
                    return description.isEmpty() ? null : new ArchitecturePlan.Risk(description,
                            enumValue(node.path("impact"), ArchitecturePlan.Impact.class,
                                    ArchitecturePlan.Impact.MEDIUM),
                            text(node, "mitigation", MAX_MEDIUM, true));
                }),
                objects(root.path("decisions"), MAX_LIST - 5, "decisions", warnings, node -> {
                    String title = text(node, "title", MAX_SHORT, false);
                    String decision = text(node, "decision", MAX_MEDIUM, true);
                    return title.isEmpty() || decision.isEmpty() ? null : new ArchitecturePlan.Decision(title,
                            decision, text(node, "rationale", MAX_MEDIUM, true),
                            strings(node.path("alternatives"), 6, MAX_SHORT, "decision alternatives", warnings));
                }),
                strings(root.path("assumptions"), MAX_LIST, MAX_MEDIUM, "assumptions", warnings),
                strings(root.path("openQuestions"), MAX_LIST - 5, MAX_MEDIUM, "openQuestions", warnings)
        );
        return new ParsedPlan(plan, warnings);
    }

    private static JsonNode readObject(String modelOutput) throws InvalidPlanException {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new InvalidPlanException("The model returned an empty plan.");
        }
        if (modelOutput.length() > MAX_OUTPUT_CHARACTERS) {
            throw new InvalidPlanException("The model output exceeds the plan size limit.");
        }
        int start = modelOutput.indexOf('{');
        int end = modelOutput.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidPlanException("The model output did not contain a JSON object.");
        }
        try {
            JsonNode root = MAPPER.readTree(modelOutput.substring(start, end + 1));
            if (root == null || !root.isObject()) {
                throw new InvalidPlanException("The model output did not contain a JSON object.");
            }
            return root;
        } catch (InvalidPlanException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException malformed) {
            throw new InvalidPlanException("The model output was not valid JSON.");
        }
    }

    private static List<ArchitecturePlan.Requirement> requirements(
            JsonNode node,
            List<String> userRequirements,
            String prefix,
            List<String> warnings
    ) {
        List<ArchitecturePlan.Requirement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String requirement : userRequirements) {
            result.add(new ArchitecturePlan.Requirement(
                    prefix + "-" + (result.size() + 1), requirement, ArchitecturePlan.Source.USER));
            seen.add(requirement.toLowerCase(Locale.ROOT));
        }
        int added = 0;
        int dropped = 0;
        if (node.isArray()) {
            for (JsonNode item : node) {
                String description = item.isTextual()
                        ? clean(item.asText(), MAX_MEDIUM, true)
                        : text(item, "description", MAX_MEDIUM, true);
                if (description.isEmpty() || !seen.add(description.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (added >= MAX_AI_REQUIREMENTS) {
                    dropped++;
                    continue;
                }
                String claimed = item.path("source").asText("").trim().toUpperCase(Locale.ROOT);
                ArchitecturePlan.Source source = claimed.startsWith("DOCUMENT")
                        ? ArchitecturePlan.Source.DOCUMENT_INFERENCE
                        : ArchitecturePlan.Source.AI_SUGGESTION;
                result.add(new ArchitecturePlan.Requirement(prefix + "-" + (result.size() + 1), description, source));
                added++;
            }
        }
        if (dropped > 0) {
            warnings.add(dropped + " suggested " + prefix + " items exceeded the limit and were removed.");
        }
        return result;
    }

    private record ComponentSet(List<ArchitecturePlan.Component> components, Map<String, String> aliases) {
        String resolve(String reference) {
            return aliases.get(reference.toLowerCase(Locale.ROOT).strip());
        }
    }

    private static ComponentSet components(JsonNode node, List<String> warnings) {
        List<ArchitecturePlan.Component> components = new ArrayList<>();
        Map<String, String> aliases = new HashMap<>();
        Set<String> ids = new HashSet<>();
        int dropped = 0;
        if (node.isArray()) {
            for (JsonNode item : node) {
                String name = text(item, "name", MAX_SHORT, false);
                if (name.isEmpty()) {
                    continue;
                }
                if (components.size() >= MAX_COMPONENTS) {
                    dropped++;
                    continue;
                }
                String rawId = item.path("id").asText("");
                String id = uniqueId(slug(rawId.isBlank() ? name : rawId, components.size() + 1), ids);
                components.add(new ArchitecturePlan.Component(
                        id,
                        name,
                        enumValue(item.path("type"), ArchitecturePlan.ComponentType.class,
                                ArchitecturePlan.ComponentType.OTHER),
                        text(item, "responsibility", MAX_MEDIUM, true),
                        strings(item.path("technologies"), 8, MAX_SHORT, "component technologies", warnings)));
                aliases.putIfAbsent(id, id);
                if (!rawId.isBlank()) {
                    aliases.putIfAbsent(rawId.toLowerCase(Locale.ROOT).strip(), id);
                }
                aliases.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
            }
        }
        if (dropped > 0) {
            warnings.add(dropped + " components exceeded the limit of " + MAX_COMPONENTS + " and were removed.");
        }
        return new ComponentSet(components, aliases);
    }

    private static List<ArchitecturePlan.Relationship> relationships(
            JsonNode node,
            ComponentSet components,
            List<String> warnings
    ) {
        List<ArchitecturePlan.Relationship> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int unresolved = 0;
        int dropped = 0;
        if (node.isArray()) {
            for (JsonNode item : node) {
                String from = components.resolve(item.path("from").asText(""));
                String to = components.resolve(item.path("to").asText(""));
                if (from == null || to == null || from.equals(to)) {
                    unresolved++;
                    continue;
                }
                String description = text(item, "description", MAX_SHORT, false);
                if (!seen.add(from + "\u0000" + to + "\u0000" + description)) {
                    continue;
                }
                if (result.size() >= MAX_RELATIONSHIPS) {
                    dropped++;
                    continue;
                }
                result.add(new ArchitecturePlan.Relationship(from, to, description));
            }
        }
        if (unresolved > 0) {
            warnings.add(unresolved + " relationships referenced unknown components and were removed.");
        }
        if (dropped > 0) {
            warnings.add(dropped + " relationships exceeded the limit of " + MAX_RELATIONSHIPS
                    + " and were removed.");
        }
        return result;
    }

    private static List<ArchitecturePlan.Phase> phases(JsonNode node, List<String> warnings) {
        List<ArchitecturePlan.Phase> result = objects(node, MAX_PHASES, "implementationPlan", warnings, item -> {
            String title = text(item, "title", MAX_SHORT, false);
            return title.isEmpty() ? null : new ArchitecturePlan.Phase(0, title,
                    text(item, "goal", MAX_MEDIUM, true),
                    strings(item.path("tasks"), MAX_PHASE_ITEMS, MAX_MEDIUM, "phase tasks", warnings),
                    strings(item.path("deliverables"), MAX_PHASE_ITEMS, MAX_MEDIUM, "phase deliverables", warnings));
        });
        List<ArchitecturePlan.Phase> ordered = new ArrayList<>(result.size());
        for (ArchitecturePlan.Phase phase : result) {
            ordered.add(new ArchitecturePlan.Phase(ordered.size() + 1, phase.title(), phase.goal(),
                    phase.tasks(), phase.deliverables()));
        }
        return ordered;
    }

    private static <T> List<T> objects(
            JsonNode node,
            int maxItems,
            String label,
            List<String> warnings,
            Function<JsonNode, T> mapper
    ) {
        List<T> result = new ArrayList<>();
        int dropped = 0;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isObject()) {
                    continue;
                }
                T value = mapper.apply(item);
                if (value == null) {
                    continue;
                }
                if (result.size() >= maxItems) {
                    dropped++;
                    continue;
                }
                result.add(value);
            }
        }
        if (dropped > 0) {
            warnings.add(dropped + " " + label + " items exceeded the limit of " + maxItems + " and were removed.");
        }
        return result;
    }

    private static List<String> strings(
            JsonNode node,
            int maxItems,
            int maxCharacters,
            String label,
            List<String> warnings
    ) {
        List<String> result = new ArrayList<>();
        int dropped = 0;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isValueNode()) {
                    continue;
                }
                String value = clean(item.asText(), maxCharacters, false);
                if (value.isEmpty() || result.contains(value)) {
                    continue;
                }
                if (result.size() >= maxItems) {
                    dropped++;
                    continue;
                }
                result.add(value);
            }
        } else if (node.isTextual()) {
            String value = clean(node.asText(), maxCharacters, false);
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        if (dropped > 0) {
            warnings.add(dropped + " " + label + " items exceeded the limit of " + maxItems + " and were removed.");
        }
        return result;
    }

    private static String text(JsonNode node, String field, int maxCharacters, boolean multiline) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? clean(value.asText(), maxCharacters, multiline) : "";
    }

    static String clean(String value, int maxCharacters, boolean multiline) {
        String normalized = UntrustedText.normalize(value, maxCharacters, multiline).text();
        String redacted = SecretRedactor.redact(normalized);
        return redacted.length() > maxCharacters ? redacted.substring(0, maxCharacters) : redacted;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, E fallback) {
        if (!node.isTextual()) {
            return fallback;
        }
        String candidate = node.asText().strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(candidate)) {
                return constant;
            }
        }
        return fallback;
    }

    static String slug(String value, int position) {
        StringBuilder slug = new StringBuilder();
        boolean dash = false;
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                slug.append(character);
                dash = false;
            } else if (!dash && !slug.isEmpty()) {
                slug.append('-');
                dash = true;
            }
            if (slug.length() >= MAX_COMPONENT_ID) {
                break;
            }
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            slug.insert(0, "c" + position + (slug.isEmpty() ? "" : "-"));
        }
        return slug.length() > MAX_COMPONENT_ID ? slug.substring(0, MAX_COMPONENT_ID) : slug.toString();
    }

    private static String uniqueId(String base, Set<String> ids) {
        String candidate = base;
        for (int suffix = 2; !ids.add(candidate); suffix++) {
            String tail = "-" + suffix;
            candidate = (base.length() + tail.length() > MAX_COMPONENT_ID
                    ? base.substring(0, MAX_COMPONENT_ID - tail.length()) : base) + tail;
        }
        return candidate;
    }

    private static String orDefault(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }
}
