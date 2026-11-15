package com.docgen;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates Mermaid flowchart source from a validated plan and validates it
 * against a deliberately tiny grammar.
 *
 * <p>The source is built from structured data only: node identifiers are
 * generated ({@code n1}, {@code n2}, ...), labels pass through a character
 * allowlist, and no directive, {@code click}, {@code style}, {@code classDef},
 * link or HTML construct is ever emitted. {@link #validate(String)} re-checks
 * every line so a future change to the generator cannot silently widen what
 * leaves the server. The web interface does not execute Mermaid; it draws the
 * same structure with its own SVG renderer, and this text is an export.</p>
 */
public final class MermaidDiagram {
    static final int MAX_LABEL = 60;
    static final int MAX_LINES = 1 + ArchitecturePlanParser.MAX_COMPONENTS + ArchitecturePlanParser.MAX_RELATIONSHIPS;

    private static final String LABEL = "\"[\\p{L}\\p{N} .,:;()/+_&'?!-]{1," + MAX_LABEL + "}\"";
    private static final Pattern NODE_LINE = Pattern.compile(
            "    n[1-9][0-9]{0,2}(?:\\[" + LABEL + "]|\\[\\(" + LABEL + "\\)]|\\(\\[" + LABEL + "]\\)"
                    + "|\\[\\[" + LABEL + "]]|\\{\\{" + LABEL + "}}|>" + LABEL + "])");
    private static final Pattern EDGE_LINE = Pattern.compile(
            "    n[1-9][0-9]{0,2} -->(?:\\|" + LABEL + "\\|)? n[1-9][0-9]{0,2}");

    private MermaidDiagram() {
    }

    public static String fromPlan(ArchitecturePlan plan) {
        StringBuilder source = new StringBuilder("flowchart LR\n");
        Map<String, String> nodes = new HashMap<>();
        List<ArchitecturePlan.Component> components = plan.components();
        for (int index = 0; index < components.size(); index++) {
            ArchitecturePlan.Component component = components.get(index);
            String node = "n" + (index + 1);
            nodes.put(component.id(), node);
            String label = "\"" + label(component.name(), component.id()) + "\"";
            source.append("    ").append(node).append(switch (component.type()) {
                case DATABASE, STORAGE -> "[(" + label + ")]";
                case CLIENT -> "([" + label + "])";
                case QUEUE -> "[[" + label + "]]";
                case EXTERNAL -> "{{" + label + "}}";
                case API_GATEWAY -> ">" + label + "]";
                default -> "[" + label + "]";
            }).append('\n');
        }
        for (ArchitecturePlan.Relationship relationship : plan.relationships()) {
            String from = nodes.get(relationship.from());
            String to = nodes.get(relationship.to());
            if (from == null || to == null) {
                continue;
            }
            source.append("    ").append(from).append(" -->");
            String description = label(relationship.description(), "");
            if (!description.isEmpty()) {
                source.append("|\"").append(description).append("\"|");
            }
            source.append(' ').append(to).append('\n');
        }
        return source.toString();
    }

    /** Rejects anything outside the generator's grammar. */
    public static void validate(String source) throws IOException {
        if (source == null || !source.startsWith("flowchart LR\n")) {
            throw new IOException("Diagram must be a left-to-right flowchart.");
        }
        String[] lines = source.split("\n", -1);
        if (lines.length - 1 > MAX_LINES + 1) {
            throw new IOException("Diagram exceeds the line limit.");
        }
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            if (!NODE_LINE.matcher(line).matches() && !EDGE_LINE.matcher(line).matches()) {
                throw new IOException("Diagram line " + index + " is outside the allowed grammar.");
            }
        }
    }

    static String label(String value, String fallback) {
        StringBuilder label = new StringBuilder();
        boolean space = false;
        String text = value == null ? "" : value;
        for (int offset = 0; offset < text.length() && label.length() < MAX_LABEL;) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean allowed = Character.isLetterOrDigit(codePoint) || ".,:;()/+_&'?!-".indexOf(codePoint) >= 0;
            if (allowed && Character.charCount(codePoint) == 1) {
                if (space && !label.isEmpty()) {
                    if (label.length() + 1 >= MAX_LABEL) {
                        break;
                    }
                    label.append(' ');
                }
                label.appendCodePoint(codePoint);
                space = false;
            } else {
                space = true;
            }
        }
        String result = label.toString().strip();
        return result.isEmpty() ? fallback : result;
    }
}
