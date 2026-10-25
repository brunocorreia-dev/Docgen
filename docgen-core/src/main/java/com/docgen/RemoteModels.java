package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Recognizes models that the local Ollama would run somewhere else (ADR 0003,
 * layer 2). Ollama can proxy a model to ollama.com or to another host; such a
 * model is listed locally like any other, so DocGen must refuse it before any
 * prompt is sent.
 *
 * <p>Two independent signals, both verified on 2026-10-25 against the Ollama
 * source ({@code api/types.go}) and empirically with Ollama 0.32.11:</p>
 * <ul>
 *   <li>{@code /api/tags}, {@code /api/show} and the {@code /api/chat} stream
 *   carry {@code remote_host} and {@code remote_model} for a remote model
 *   (a stub created with {@code remote_host} came back with both set, a size of
 *   191 bytes and an empty {@code details.format});</li>
 *   <li>Ollama names cloud models with a {@code cloud} tag, such as
 *   {@code gemma4:cloud} or {@code gpt-oss:120b-cloud} (docs.ollama.com/cloud).</li>
 * </ul>
 * <p>Either signal is enough to refuse. The catalog allowlist (layer 1) is the
 * primary control in the app; this is the backstop that also covers the CLI.</p>
 */
public final class RemoteModels {
    private RemoteModels() {
    }

    /** A tag of {@code cloud} or ending in {@code -cloud}, Ollama's naming for cloud models. */
    public static boolean isCloudName(String model) {
        if (model == null) {
            return false;
        }
        int colon = model.lastIndexOf(':');
        if (colon < 0 || colon < model.lastIndexOf('/')) {
            return false;
        }
        String tag = model.substring(colon + 1).toLowerCase(Locale.ROOT);
        return tag.equals("cloud") || tag.endsWith("-cloud");
    }

    /**
     * Whether a model entry or a chat chunk declares a remote host or model.
     * Any non-empty value counts, including one of an unexpected type: unknown
     * shapes fail closed.
     */
    static boolean declaresRemote(JsonNode node) {
        return isSet(node.path("remote_host")) || isSet(node.path("remote_model"));
    }

    private static boolean isSet(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return false;
        }
        return !value.isTextual() || !value.asText().isBlank();
    }

    /** Refuses a model whose name or metadata says it runs outside this computer. */
    static void requireLocal(String model, boolean declaredRemote) throws RemoteModelRefusedException {
        if (declaredRemote || isCloudName(model)) {
            throw new RemoteModelRefusedException(model);
        }
    }
}
