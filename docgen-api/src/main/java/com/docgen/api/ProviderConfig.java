package com.docgen.api;

import com.docgen.ArchitecturePlanningService;
import com.docgen.DocumentationService;
import com.docgen.LLMProviderFactory;
import com.docgen.LLMProviderResolver;
import com.docgen.OllamaEndpoint;
import com.docgen.ProjectDocumentationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Provider wiring. The only provider is the local Ollama service, whose
 * loopback endpoint is fixed in the core; nothing here is configurable from a
 * request. Tests replace the resolver bean with a fake so they never reach a
 * real model.
 */
@Configuration(proxyBeanMethods = false)
class ProviderConfig {
    @Bean
    LLMProviderResolver llmProviderResolver(DocGenApiProperties properties, SettingsService settings) {
        OllamaEndpoint endpoint = properties.ollama().endpoint();
        return model -> LLMProviderFactory.create(
                modelFor(model, () -> settings.defaultModel(SessionTokenService.OWNER)), endpoint);
    }

    /** A project without a model uses the app's default model, and the CLI default without one. */
    static String modelFor(String requested, Supplier<Optional<String>> appDefault) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return appDefault.get().orElse(LLMProviderFactory.DEFAULT_MODEL);
    }

    @Bean
    DocumentationService documentationService(LLMProviderResolver resolver) {
        return new DocumentationService(resolver);
    }

    @Bean
    ArchitecturePlanningService architecturePlanningService(LLMProviderResolver resolver) {
        return new ArchitecturePlanningService(resolver);
    }

    @Bean
    ProjectDocumentationService projectDocumentationService(LLMProviderResolver resolver) {
        return new ProjectDocumentationService(resolver);
    }
}
