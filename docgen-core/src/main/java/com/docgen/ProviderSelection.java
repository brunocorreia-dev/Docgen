package com.docgen;

import java.util.regex.Pattern;

/**
 * The local Ollama model used for one operation ({@code null} selects the
 * default). Model names are restricted to a conservative character set because
 * they are echoed into provider requests and the user interface.
 */
public record ProviderSelection(String model) {
    /** The only provider DocGen supports, kept for records that store a provider name. */
    public static final String PROVIDER = "ollama";
    public static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");

    public ProviderSelection {
        model = model == null || model.isBlank() ? null : model.strip();
        if (model != null && !MODEL.matcher(model).matches()) {
            throw new IllegalArgumentException("Invalid model name");
        }
    }

    public String provider() {
        return PROVIDER;
    }
}
