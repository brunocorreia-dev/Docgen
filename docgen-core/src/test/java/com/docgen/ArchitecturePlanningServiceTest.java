package com.docgen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitecturePlanningServiceTest {
    @Test
    void plansWithTheStructuredModeOfALocalProviderWithoutAskingForConsent() throws Exception {
        FakeProvider provider = new FakeProvider(false, PlanFixtures.validPlanJson());
        ArchitecturePlanningService service = new ArchitecturePlanningService((name, model, allowRemote) -> provider);
        ArchitecturePlanningService.Preparation preparation = service.prepare(PlanFixtures.brief(),
                List.of(new SupportingDocument("spec.pdf", "Requisito: relatórios mensais", false)));

        ArchitecturePlanningService.Result result = service.plan(preparation,
                new ProviderSelection("ollama", null, false), transmission -> {
                    throw new AssertionError("local providers must not ask for remote consent");
                });

        assertEquals(1, provider.structuredCalls);
        assertEquals(0, provider.documentationCalls);
        assertTrue(provider.prompts.getFirst().contains("relatórios mensais"));
        assertEquals(3, result.plan().components().size());
        assertTrue(result.mermaid().startsWith("flowchart LR\n"));
        assertEquals(1, preparation.documentCount());
    }

    @Test
    void remoteProvidersRequireTheFlagAndExplicitConsentForTheExactPayload() throws Exception {
        FakeProvider provider = new FakeProvider(true, PlanFixtures.validPlanJson());
        ArchitecturePlanningService service = new ArchitecturePlanningService((name, model, allowRemote) -> provider);
        ArchitecturePlanningService.Preparation preparation = service.prepare(PlanFixtures.brief(), List.of());

        assertThrows(IllegalArgumentException.class, () -> service.plan(preparation,
                new ProviderSelection("groq", null, false), transmission -> true));
        assertThrows(DocumentationService.RemoteConsentDeniedException.class, () -> service.plan(preparation,
                new ProviderSelection("groq", null, true), transmission -> false));
        assertEquals(0, provider.structuredCalls);

        AtomicReference<DocumentationService.RemoteTransmission> seen = new AtomicReference<>();
        service.plan(preparation, new ProviderSelection("groq", null, true), transmission -> {
            seen.set(transmission);
            return true;
        });
        assertEquals("fake remote", seen.get().destination());
        assertEquals(preparation.characterCount(), seen.get().totalCharacters());
        assertEquals(1, provider.structuredCalls);
    }

    @Test
    void invalidModelOutputFailsInsteadOfProducingAPlan() {
        FakeProvider provider = new FakeProvider(false, "Sure! Here is your architecture: microservices.");
        ArchitecturePlanningService service = new ArchitecturePlanningService((name, model, allowRemote) -> provider);

        assertThrows(ArchitecturePlanParser.InvalidPlanException.class, () -> service.plan(
                service.prepare(PlanFixtures.brief(), List.of()),
                new ProviderSelection("ollama", null, false), transmission -> true));
    }

    @Test
    void providerFailuresPropagate() {
        ArchitecturePlanningService service = new ArchitecturePlanningService((name, model, allowRemote) ->
                new FakeProvider(false, (String) null));

        assertThrows(java.io.IOException.class, () -> service.plan(
                service.prepare(PlanFixtures.brief(), List.of()),
                new ProviderSelection("ollama", null, false), transmission -> true));
    }

    @Test
    void digestsBindTheProviderModelAndPrompt() {
        ArchitecturePlanningService service = new ArchitecturePlanningService((name, model, allowRemote) -> null);
        ArchitecturePlanningService.Preparation preparation = service.prepare(PlanFixtures.brief(), List.of());
        ProviderSelection groq = new ProviderSelection("groq", null, true);

        assertEquals(preparation.digest(groq), service.prepare(PlanFixtures.brief(), List.of()).digest(groq));
        assertTrue(!preparation.digest(groq).equals(preparation.digest(new ProviderSelection("groq", "other", true))));
        assertTrue(!preparation.digest(groq).equals(service.prepare(PlanFixtures.brief(),
                List.of(new SupportingDocument("a.pdf", "changed", false))).digest(groq)));
    }

    @Test
    void providerSelectionRejectsUnknownProvidersAndUnsafeModelNames() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderSelection("openai", null, false));
        assertThrows(IllegalArgumentException.class, () -> new ProviderSelection("ollama", "llama\n3", false));
        assertThrows(IllegalArgumentException.class, () -> new ProviderSelection("ollama", "../x", false));
        assertEquals("groq", new ProviderSelection(null, null, false).provider());
        assertEquals("llama3.2:3b", new ProviderSelection("OLLAMA", "llama3.2:3b", false).model());
    }

    static final class FakeProvider implements LLMProvider {
        private final boolean remote;
        private final List<String> responses;
        final List<String> prompts = new ArrayList<>();
        int structuredCalls;
        int documentationCalls;

        FakeProvider(boolean remote, String... responses) {
            this.remote = remote;
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

        @Override
        public boolean isRemote() {
            return remote;
        }

        @Override
        public String describeDestination() {
            return remote ? "fake remote" : "fake local";
        }
    }
}
