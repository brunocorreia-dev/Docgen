package com.docgen;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Provider, model and the operator's remote-transmission flag for one
 * operation. Model names are restricted to a conservative character set
 * because they are echoed into provider requests and the user interface.
 */
public record ProviderSelection(String provider, String model, boolean allowRemote) {
    public static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");

    public ProviderSelection {
        String selected = provider == null || provider.isBlank() ? "groq" : provider.strip().toLowerCase(Locale.ROOT);
        if (LLMProviderFactory.supportedProviders().stream().noneMatch(item -> item.id().equals(selected))) {
            throw new IllegalArgumentException("Unsupported provider");
        }
        provider = selected;
        model = model == null || model.isBlank() ? null : model.strip();
        if (model != null && !MODEL.matcher(model).matches()) {
            throw new IllegalArgumentException("Invalid model name");
        }
    }
}
