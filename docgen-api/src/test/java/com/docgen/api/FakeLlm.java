package com.docgen.api;

import com.docgen.LLMProvider;
import com.docgen.LLMProviderResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Scriptable stand-in for the local Ollama provider. Tests never reach a real
 * model: the resolver bean is replaced by {@link #resolver()} in {@link ApiTestSupport}.
 */
final class FakeLlm {
    @FunctionalInterface
    interface Script {
        String respond(String prompt) throws Exception;
    }

    private static final Deque<Script> SCRIPTS = new ArrayDeque<>();
    private static final List<String> PROMPTS = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> MODELS = Collections.synchronizedList(new ArrayList<>());
    private static volatile Script fallback = prompt -> {
        throw new IllegalStateException("No scripted response");
    };

    private FakeLlm() {
    }

    static synchronized void reset() {
        SCRIPTS.clear();
        PROMPTS.clear();
        MODELS.clear();
        fallback = prompt -> {
            throw new IllegalStateException("No scripted response");
        };
    }

    static synchronized void enqueue(Script... scripts) {
        SCRIPTS.addAll(List.of(scripts));
    }

    static void always(Script script) {
        fallback = script;
    }

    static List<String> prompts() {
        return List.copyOf(PROMPTS);
    }

    /** Models requested from the resolver, in order; {@code null} means the default model. */
    static List<String> models() {
        synchronized (MODELS) {
            return new ArrayList<>(MODELS);
        }
    }

    /** A script that blocks interruptibly until released, like a slow provider call. */
    static Script blocking(CountDownLatch started, CountDownLatch release, String response) {
        return prompt -> {
            started.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not released");
            }
            return response;
        };
    }

    private static String next(String prompt) throws Exception {
        PROMPTS.add(prompt);
        Script script;
        synchronized (FakeLlm.class) {
            script = SCRIPTS.isEmpty() ? fallback : SCRIPTS.removeFirst();
        }
        return script.respond(prompt);
    }

    static LLMProviderResolver resolver() {
        return model -> {
            MODELS.add(model);
            return new LLMProvider() {
                @Override
                public String generateDocumentation(String prompt) throws Exception {
                    return next(prompt);
                }

                @Override
                public String generateStructured(String prompt) throws Exception {
                    return next(prompt);
                }
            };
        };
    }
}
