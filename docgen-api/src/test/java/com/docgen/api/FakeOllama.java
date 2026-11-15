package com.docgen.api;

import com.docgen.Cancellation;
import com.docgen.ChatOutcome;
import com.docgen.ChatRequest;
import com.docgen.InstalledModel;
import com.docgen.LoadedModel;
import com.docgen.ModelDescription;
import com.docgen.ModelNotInstalledException;
import com.docgen.OllamaApi;
import com.docgen.OllamaUnavailableException;
import com.docgen.PullProgress;
import com.docgen.RemoteModelRefusedException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * In-memory stand-in for the local Ollama, so API tests never reach a real
 * one. It is a singleton bean shared by cached test contexts: every test
 * starts from {@link #reset()}.
 */
final class FakeOllama implements OllamaApi {
    @FunctionalInterface
    interface PullScript {
        void run(String model, PullListener listener, Cancellation cancellation) throws Exception;
    }

    static final String LAYER = "sha256:" + "a".repeat(64);

    private volatile boolean reachable;
    private final List<InstalledModel> installed = new CopyOnWriteArrayList<>();
    private final List<LoadedModel> loaded = new CopyOnWriteArrayList<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile PullScript pullScript;
    private volatile String answer;
    private volatile Exception chatFailure;
    final List<ChatRequest> chats = new CopyOnWriteArrayList<>();

    FakeOllama() {
        reset();
    }

    void reset() {
        reachable = true;
        installed.clear();
        loaded.clear();
        calls.clear();
        chats.clear();
        answer = "Olá! Estou funcionando.";
        chatFailure = null;
        pullScript = (model, listener, cancellation) -> {
            listener.onProgress(new PullProgress(PullProgress.Stage.MANIFEST, null, 0, 0));
            listener.onProgress(new PullProgress(PullProgress.Stage.DOWNLOADING, LAYER, 1_000, 400));
            listener.onProgress(new PullProgress(PullProgress.Stage.DOWNLOADING, LAYER, 1_000, 1_000));
            listener.onProgress(new PullProgress(PullProgress.Stage.SUCCESS, null, 0, 0));
            install(model, false);
        };
    }

    FakeOllama reachable(boolean value) {
        reachable = value;
        return this;
    }

    FakeOllama install(String name, boolean remote) {
        installed.add(new InstalledModel(name, remote ? 191 : 5_000_000_000L, remote ? null : "500a1f067a9f",
                remote ? null : "qwen3", remote ? null : "8.2B", remote ? null : "Q4_K_M", remote ? null : 40_960,
                List.of("completion"), "2026-10-25T10:00:00-03:00", remote));
        return this;
    }

    FakeOllama load(String name, long size, long vram) {
        loaded.add(new LoadedModel(name, size, vram, 8_192, "2026-10-25T10:05:00-03:00"));
        return this;
    }

    FakeOllama pulls(PullScript script) {
        pullScript = script;
        return this;
    }

    /** A pull that reports progress and then waits until it is cancelled (or 30 s pass). */
    static PullScript blockingPull(CountDownLatch started) {
        return (model, listener, cancellation) -> {
            listener.onProgress(new PullProgress(PullProgress.Stage.DOWNLOADING, LAYER, 1_000, 250));
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!cancellation.isCancelled()) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("The fake pull was never cancelled");
                }
                Thread.sleep(10);
            }
            throw new CancellationException("cancelled");
        };
    }

    FakeOllama answers(String text) {
        answer = text;
        return this;
    }

    FakeOllama chatFails(Exception failure) {
        chatFailure = failure;
        return this;
    }

    List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    private void requireReachable(String call) throws OllamaUnavailableException {
        calls.add(call);
        if (!reachable) {
            throw new OllamaUnavailableException();
        }
    }

    @Override
    public String version() throws IOException {
        requireReachable("version");
        return "0.32.11";
    }

    @Override
    public List<InstalledModel> installedModels() throws IOException {
        requireReachable("tags");
        return List.copyOf(installed);
    }

    @Override
    public ModelDescription describe(String model) throws IOException {
        requireReachable("show " + model);
        InstalledModel found = installed.stream().filter(candidate -> candidate.name().equals(model)).findFirst()
                .orElseThrow(() -> new ModelNotInstalledException(model));
        return new ModelDescription(model, found.remote() ? null : "qwen3", found.contextLength(), 8_190_000_000L,
                found.parameterSize(), found.quantization(), found.family(), found.capabilities(), 36, 8, 128,
                found.remote());
    }

    @Override
    public List<LoadedModel> loadedModels() throws IOException {
        requireReachable("ps");
        return List.copyOf(loaded);
    }

    @Override
    public void delete(String model) throws IOException {
        requireReachable("delete " + model);
        if (!installed.removeIf(candidate -> candidate.name().equals(model))) {
            throw new ModelNotInstalledException(model);
        }
    }

    @Override
    public void pull(String model, PullListener listener, Cancellation cancellation) throws IOException {
        requireReachable("pull " + model);
        try {
            pullScript.run(model, listener, cancellation);
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
    }

    @Override
    public ChatOutcome chat(ChatRequest request, ChatListener listener, Cancellation cancellation) throws IOException {
        requireReachable("chat " + request.model());
        ModelDescription model = describe(request.model());
        if (model.remote()) {
            throw new RemoteModelRefusedException(request.model());
        }
        chats.add(request);
        if (chatFailure instanceof IOException failure) {
            throw failure;
        }
        listener.onThinking("raciocínio que nunca aparece");
        listener.onContent(answer);
        return new ChatOutcome("stop", 30, 6, Duration.ofMillis(1_200), Duration.ofMillis(1_500), Duration.ofMillis(60));
    }
}
