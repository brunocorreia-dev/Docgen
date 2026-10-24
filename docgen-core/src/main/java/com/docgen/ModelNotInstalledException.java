package com.docgen;

import java.io.IOException;

/** The local Ollama does not have the requested model. DocGen never downloads a model on its own. */
public final class ModelNotInstalledException extends IOException {
    private final String model;

    public ModelNotInstalledException(String model) {
        super("Model " + model + " is not installed in the local Ollama. DocGen never downloads models by itself.");
        this.model = model;
    }

    public String model() {
        return model;
    }
}
