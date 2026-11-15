package com.docgen.api;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.DeserializationFeature;

/**
 * Strict request parsing enforced in code. The equivalent YAML switch is not
 * enough on its own: an application.yml on the test classpath shadows the main
 * one, which silently disabled unknown-property rejection in the Phase 02 tests.
 */
@Configuration(proxyBeanMethods = false)
class StrictJsonConfig {
    static final int MAX_NESTING_DEPTH = 32;
    static final int MAX_STRING_LENGTH = 256 * 1024;

    @Bean
    JsonMapperBuilderCustomizer strictJsonMapper() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    @Bean
    JsonFactoryBuilderCustomizer boundedJsonFactory() {
        return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .build());
    }
}
