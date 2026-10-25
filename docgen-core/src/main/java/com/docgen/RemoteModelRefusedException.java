package com.docgen;

import java.io.IOException;

/**
 * The model would run outside this computer (an Ollama cloud model or a model
 * proxied to another host), so DocGen refuses it before sending anything
 * (ADR 0003).
 */
public final class RemoteModelRefusedException extends IOException {
    private final String model;

    public RemoteModelRefusedException(String model) {
        super("Model " + model + " runs outside this computer (Ollama cloud or another host),"
                + " so DocGen refused to use it. DocGen only runs models on this computer.");
        this.model = model;
    }

    public String model() {
        return model;
    }
}
