package com.docgen.api;

import com.docgen.ArchitecturePlanningService;
import com.docgen.DocumentationService;
import com.docgen.LLMProvider;
import com.docgen.LLMProviderFactory;
import com.docgen.LLMProviderResolver;
import com.docgen.ProjectDocumentationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider wiring. Provider endpoints stay fixed in the core (Groq HTTPS API,
 * Ollama on 127.0.0.1) and the Groq key is read from the server environment
 * only; nothing here is configurable from a request. Tests replace the
 * resolver bean with a fake so they never reach a real provider.
 */
@Configuration(proxyBeanMethods = false)
class ProviderConfig {
    @Bean
    LLMProviderResolver llmProviderResolver() {
        return LLMProviderFactory::create;
    }

    @Bean
    DocumentationService documentationService() {
        return new DocumentationService();
    }

    @Bean
    ArchitecturePlanningService architecturePlanningService(LLMProviderResolver resolver) {
        return new ArchitecturePlanningService(resolver);
    }

    @Bean
    ProjectDocumentationService projectDocumentationService(LLMProviderResolver resolver) {
        return new ProjectDocumentationService(resolver);
    }

    /** Describes where a selection would send data, without any network access. */
    static String destination(LLMProviderResolver resolver, String provider, String model) {
        LLMProvider client = resolver.create(provider, model, true);
        return client.describeDestination();
    }

    static boolean groqKeyConfigured() {
        String key = System.getenv("GROQ_API_KEY");
        return key != null && !key.isBlank();
    }
}
