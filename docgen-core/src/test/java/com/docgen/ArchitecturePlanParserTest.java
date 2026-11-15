package com.docgen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitecturePlanParserTest {
    private final ArchitecturePlanParser parser = new ArchitecturePlanParser();

    @Test
    void parsesAStructuredPlanAndSlugsComponentIdentifiers() throws Exception {
        ArchitecturePlanParser.ParsedPlan parsed = parser.parse(PlanFixtures.validPlanJson(), PlanFixtures.brief());
        ArchitecturePlan plan = parsed.plan();

        assertEquals("API REST para controle de estoque.", plan.summary());
        assertEquals(List.of("web-client", "api", "db"),
                plan.components().stream().map(ArchitecturePlan.Component::id).toList());
        assertEquals(ArchitecturePlan.ComponentType.DATABASE, plan.components().get(2).type());
        assertEquals(List.of(1, 2), plan.implementationPlan().stream().map(ArchitecturePlan.Phase::order).toList());
        assertEquals(ArchitecturePlan.Impact.HIGH, plan.risks().getFirst().impact());
    }

    @Test
    void userRequirementsComeFromTheBriefAndTheModelCannotClaimUserSource() throws Exception {
        ArchitecturePlan plan = parser.parse(PlanFixtures.validPlanJson(), PlanFixtures.brief()).plan();

        List<ArchitecturePlan.Requirement> functional = plan.functionalRequirements();
        assertEquals("Cadastrar produtos", functional.get(0).description());
        assertEquals(ArchitecturePlan.Source.USER, functional.get(0).source());
        assertEquals(ArchitecturePlan.Source.USER, functional.get(1).source());
        // Duplicate of a user requirement is not repeated.
        assertEquals(1, functional.stream().filter(item -> item.description().equals("Cadastrar produtos")).count());
        // "Exportar inventário em CSV" claimed USER but the user never wrote it.
        ArchitecturePlan.Requirement forged = functional.stream()
                .filter(item -> item.description().startsWith("Exportar")).findFirst().orElseThrow();
        assertEquals(ArchitecturePlan.Source.AI_SUGGESTION, forged.source());
        assertEquals(ArchitecturePlan.Source.DOCUMENT_INFERENCE,
                plan.nonFunctionalRequirements().get(1).source());
        assertEquals(List.of("FR-1", "FR-2", "FR-3", "FR-4"),
                functional.stream().map(ArchitecturePlan.Requirement::id).toList());
    }

    @Test
    void dropsRelationshipsToUnknownComponentsAndReportsIt() throws Exception {
        ArchitecturePlanParser.ParsedPlan parsed = parser.parse(PlanFixtures.validPlanJson(), PlanFixtures.brief());

        assertEquals(2, parsed.plan().relationships().size());
        assertEquals("web-client", parsed.plan().relationships().getFirst().from());
        assertTrue(parsed.warnings().stream().anyMatch(item -> item.contains("unknown components")));
    }

    @Test
    void acceptsJsonWrappedInACodeFence() throws Exception {
        String fenced = "Here is the plan:\n```json\n" + PlanFixtures.validPlanJson() + "\n```";

        assertEquals(3, parser.parse(fenced, PlanFixtures.brief()).plan().components().size());
    }

    @Test
    void redactsSecretsAndStripsControlCharactersFromModelStrings() throws Exception {
        String json = PlanFixtures.validPlanJson().replace(
                "API REST para controle de estoque.",
                "Use a chave gsk_abcdefghijklmnopqrstuvwxyz123456\\u202E e \\u001b[2J");

        String summary = parser.parse(json, PlanFixtures.brief()).plan().summary();

        assertFalse(summary.contains("gsk_abcdefghijklmnopqrstuvwxyz123456"));
        assertTrue(summary.contains("[REDACTED_SECRET]"));
        assertFalse(summary.contains("\u202E"));
        assertFalse(summary.contains("\u001b"));
    }

    @Test
    void capsComponentAndRelationshipCounts() throws Exception {
        String components = IntStream.range(0, 45)
                .mapToObj(index -> "{\"id\":\"c" + index + "\",\"name\":\"Component " + index + "\",\"type\":\"SERVICE\"}")
                .collect(Collectors.joining(","));
        String json = "{\"summary\":\"s\",\"components\":[" + components + "]}";

        ArchitecturePlanParser.ParsedPlan parsed = parser.parse(json, PlanFixtures.brief());

        assertEquals(ArchitecturePlanParser.MAX_COMPONENTS, parsed.plan().components().size());
        assertTrue(parsed.warnings().stream().anyMatch(item -> item.contains("components exceeded")));
    }

    @Test
    void unknownEnumerationsFallBackToSafeDefaults() throws Exception {
        String json = "{\"summary\":\"s\",\"components\":[{\"id\":\"x\",\"name\":\"X\",\"type\":\"<script>\"}],"
                + "\"risks\":[{\"description\":\"r\",\"impact\":\"CATASTROPHIC\"}]}";

        ArchitecturePlan plan = parser.parse(json, PlanFixtures.brief()).plan();

        assertEquals(ArchitecturePlan.ComponentType.OTHER, plan.components().getFirst().type());
        assertEquals(ArchitecturePlan.Impact.MEDIUM, plan.risks().getFirst().impact());
        assertEquals("Not specified", plan.architecture().style());
    }

    @Test
    void rejectsOutputWithoutJsonSummaryOrComponents() {
        assertThrows(ArchitecturePlanParser.InvalidPlanException.class,
                () -> parser.parse("no json here", PlanFixtures.brief()));
        assertThrows(ArchitecturePlanParser.InvalidPlanException.class,
                () -> parser.parse("{\"summary\": \"x\", \"components\": [}", PlanFixtures.brief()));
        assertThrows(ArchitecturePlanParser.InvalidPlanException.class,
                () -> parser.parse("{\"components\":[{\"name\":\"a\"}]}", PlanFixtures.brief()));
        assertThrows(ArchitecturePlanParser.InvalidPlanException.class,
                () -> parser.parse("{\"summary\":\"x\",\"components\":[]}", PlanFixtures.brief()));
        assertThrows(ArchitecturePlanParser.InvalidPlanException.class,
                () -> parser.parse("{".repeat(40) + "}".repeat(40), PlanFixtures.brief()));
        assertThrows(ArchitecturePlanParser.InvalidPlanException.class,
                () -> parser.parse("x".repeat(ArchitecturePlanParser.MAX_OUTPUT_CHARACTERS + 1), PlanFixtures.brief()));
    }

    @Test
    void slugsAreRestrictedAndUnique() {
        assertEquals("api-gateway", ArchitecturePlanParser.slug("API Gateway!", 1));
        assertEquals("c2-9lives", ArchitecturePlanParser.slug("9lives", 2));
        assertEquals("click-n1", ArchitecturePlanParser.slug("\"]; click n1", 3));
        assertTrue(ArchitecturePlanParser.slug("x".repeat(100), 1).length() <= ArchitecturePlanParser.MAX_COMPONENT_ID);
    }

    @Test
    void storedPlansRoundTripStrictly() throws Exception {
        ArchitecturePlan plan = parser.parse(PlanFixtures.validPlanJson(), PlanFixtures.brief()).plan();

        assertEquals(plan, ArchitecturePlanJson.read(ArchitecturePlanJson.write(plan)));
        assertThrows(Exception.class, () -> ArchitecturePlanJson.read(
                ArchitecturePlanJson.write(plan).replaceFirst("\\{", "{\"injected\":true,")));
    }
}
