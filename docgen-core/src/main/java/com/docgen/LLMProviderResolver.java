package com.docgen;

/**
 * Creates a provider client for a model ({@code null} selects the default);
 * the default is {@link LLMProviderFactory#create}.
 */
@FunctionalInterface
public interface LLMProviderResolver {
    LLMProvider create(String model);
}
