package com.docgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MermaidDiagramTest {
    @Test
    void generatesAFlowchartWithShapesPerComponentType() throws Exception {
        ArchitecturePlan plan = new ArchitecturePlanParser().parse(PlanFixtures.validPlanJson(), PlanFixtures.brief()).plan();

        String source = MermaidDiagram.fromPlan(plan);

        assertEquals("""
                flowchart LR
                    n1(["Painel Web"])
                    n2["API de Estoque"]
                    n3[("PostgreSQL")]
                    n1 -->|"REST/HTTPS"| n2
                    n2 -->|"JDBC"| n3
                """, source);
        assertDoesNotThrow(() -> MermaidDiagram.validate(source));
    }

    @Test
    void hostileLabelsCannotEscapeTheQuotedLabel() {
        ArchitecturePlan plan = planWithComponent(
                "Evil\"] \nclick n1 \"javascript:alert(1)\" %%{init: {'securityLevel':'loose'}}%% <img src=x onerror=alert(1)>");

        String source = MermaidDiagram.fromPlan(plan);

        assertFalse(source.contains("javascript:alert(1)\""));
        assertFalse(source.contains("%%"));
        assertFalse(source.contains("<"));
        assertFalse(source.contains("{init"));
        assertEquals(2, source.split("\n").length);
        assertDoesNotThrow(() -> MermaidDiagram.validate(source));
    }

    @Test
    void validatorRejectsDirectivesInteractionsStylesAndHtml() {
        for (String line : List.of(
                "    click n1 \"https://example.com\"",
                "%%{init: {\"securityLevel\": \"loose\"}}%%",
                "    style n1 fill:#f00",
                "    classDef evil fill:#f00",
                "    n1[\"<b>bold</b>\"]",
                "    n1[\"label\"] --> n2",
                "    subgraph x")) {
            assertThrows(IOException.class, () -> MermaidDiagram.validate("flowchart LR\n" + line + "\n"), line);
        }
        assertThrows(IOException.class, () -> MermaidDiagram.validate("graph TD\n"));
    }

    @Test
    void emptyLabelsFallBackToTheComponentIdentifier() {
        ArchitecturePlan plan = planWithComponent("<<<>>>");

        assertEquals("flowchart LR\n    n1[\"svc\"]\n", MermaidDiagram.fromPlan(plan));
    }

    private static ArchitecturePlan planWithComponent(String name) {
        return new ArchitecturePlan("s", List.of(), List.of(), List.of(),
                new ArchitecturePlan.ArchitectureStyle("x", ""),
                List.of(new ArchitecturePlan.Component("svc", name, ArchitecturePlan.ComponentType.SERVICE, "", List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                new ArchitecturePlan.Persistence("x", List.of()), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
