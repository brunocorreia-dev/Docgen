package com.docgen;

/** Creates a provider client for a selection; the default is {@link LLMProviderFactory#create}. */
@FunctionalInterface
public interface LLMProviderResolver {
    LLMProvider create(String provider, String model, boolean allowRemote);
}
