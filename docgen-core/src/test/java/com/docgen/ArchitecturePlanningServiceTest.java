package com.docgen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitecturePlanningServiceTest {
    @Test
    void plansWithTheStructuredModeOfTheLocalProvider() throws Exception {
        FakeProvider provider = new FakeProvider(PlanFixtures.validPlanJson());
        ArchitecturePlanningService service = new ArchitecturePlanningService(model -> provider);
        ArchitecturePlanningService.Preparation preparation = service.prepare(PlanFixtures.brief(),
                List.of(new SupportingDocument("spec.pdf", "Requisito: relatórios mensais", false)));

        ArchitecturePlanningService.Result result = service.plan(preparation, new ProviderSelection(null));

        assertEquals(1, provider.structuredCalls);
        assertEquals(0, provider.documentationCalls);
        assertTrue(provider.prompts.getFirst().contains("relatórios mensais"));
        assertEquals(3, result.plan().components().size());
        assertTrue(result.mermaid().startsWith("flowchart LR\n"));
        assertEquals(1, preparation.documentCount());
    }

    @Test
    void invalidModelOutputFailsInsteadOfProducingAPlan() {
        FakeProvider provider = new FakeProvider("Sure! Here is your architecture: microservices.");
        ArchitecturePlanningService service = new ArchitecturePlanningService(model -> provider);

        assertThrows(ArchitecturePlanParser.InvalidPlanException.class, () -> service.plan(
                service.prepare(PlanFixtures.brief(), List.of()),
                new ProviderSelection(null)));
    }

    @Test
    void providerFailuresPropagate() {
        ArchitecturePlanningService service = new ArchitecturePlanningService(model ->
                new FakeProvider((String) null));

        assertThrows(java.io.IOException.class, () -> service.plan(
                service.prepare(PlanFixtures.brief(), List.of()),
                new ProviderSelection(null)));
    }

    @Test
    void plansWithTheSelectedModel() throws Exception {
        FakeProvider provider = new FakeProvider(PlanFixtures.validPlanJson());
        List<String> models = new ArrayList<>();
        ArchitecturePlanningService service = new ArchitecturePlanningService(model -> {
            models.add(model);
            return provider;
        });

        service.plan(service.prepare(PlanFixtures.brief(), List.of()), new ProviderSelection("qwen2.5-coder:7b"));

        assertEquals(List.of("qwen2.5-coder:7b"), models);
    }

    @Test
    void providerSelectionIsAlwaysOllamaAndRejectsUnsafeModelNames() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderSelection("llama\n3"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderSelection("../x"));
        assertEquals("ollama", new ProviderSelection(null).provider());
        assertEquals(null, new ProviderSelection("  ").model());
        assertEquals("qwen2.5-coder:7b", new ProviderSelection(" qwen2.5-coder:7b ").model());
    }

    static final class FakeProvider implements LLMProvider {
        private final List<String> responses;
        final List<String> prompts = new ArrayList<>();
        int structuredCalls;
        int documentationCalls;

        FakeProvider(String... responses) {
            this.responses = new ArrayList<>(java.util.Arrays.asList(responses));
        }

        @Override
        public String generateDocumentation(String prompt) throws Exception {
            documentationCalls++;
            return next(prompt);
        }

        @Override
        public String generateStructured(String prompt) throws Exception {
            structuredCalls++;
            return next(prompt);
        }

        private String next(String prompt) throws java.io.IOException {
            prompts.add(prompt);
            String response = responses.size() > 1 ? responses.removeFirst() : responses.getFirst();
            if (response == null) {
                throw new java.io.IOException("provider unavailable");
            }
            return response;
        }
    }
}
