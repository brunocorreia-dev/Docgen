package com.docgen.api;

import com.docgen.Cancellation;
import com.docgen.InstalledModel;
import com.docgen.ModelPullException;
import com.docgen.OllamaApi;
import com.docgen.OllamaUnavailableException;
import com.docgen.PullProgress;
import com.docgen.RemoteModelRefusedException;
import com.docgen.hardware.HardwareProbe;
import com.docgen.hardware.HardwareProfile;
import com.docgen.models.CatalogModel;
import com.docgen.models.ModelCatalog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Model downloads, one at a time, done by Ollama itself ({@code /api/pull}) and
 * only after the user's click (ADR 0003): only catalog tags, never a model
 * that is already installed, and never when the disk is clearly too small.
 * Progress comes from Ollama's own events; the UI polls the snapshot. A
 * running download keeps the desktop app from its idle shutdown (ADR 0001).
 */
@Service
class ModelPullService {
    private static final Logger LOG = LoggerFactory.getLogger(ModelPullService.class);
    static final int MAX_KEPT = 10;
    static final Duration SPEED_WINDOW = Duration.ofSeconds(5);

    enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private final OllamaApi ollama;
    private final ModelCatalog catalog;
    private final HardwareProbe hardware;
    private final ActivityTracker activity;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "docgen-model-pull");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Pull> pulls = new LinkedHashMap<>();

    ModelPullService(OllamaApi ollama, ModelCatalog catalog, HardwareProbe hardware, ActivityTracker activity,
                     Clock clock) {
        this.ollama = ollama;
        this.catalog = catalog;
        this.hardware = hardware;
        this.activity = activity;
        this.clock = clock;
    }

    synchronized ModelDtos.PullView start(String model) {
        CatalogModel entry = catalog.find(model).orElseThrow(OllamaFailures::notInCatalog);
        if (pulls.values().stream().anyMatch(Pull::running)) {
            throw new ConflictException("pull_in_progress", "Another model is being downloaded.");
        }
        List<InstalledModel> installed;
        try {
            installed = ollama.installedModels();
        } catch (Exception failure) {
            throw OllamaFailures.translate(failure);
        }
        if (installed.stream().anyMatch(candidate -> candidate.name().equals(model))) {
            throw new ConflictException("already_installed", "The model is already installed.");
        }
        HardwareProfile.Storage disk = hardware.modelStorage();
        if (disk.freeBytes() != null && disk.freeBytes() < entry.downloadBytes()) {
            throw new StatusException(HttpStatus.INSUFFICIENT_STORAGE, "insufficient_disk",
                    "There is not enough free disk space for this model.");
        }
        Pull pull = new Pull(Ids.random(), model, entry.downloadBytes(), clock);
        pulls.put(pull.id, pull);
        trim();
        // The answer describes the download as it starts, even if a fast one finishes before the response is sent.
        ModelDtos.PullView started = pull.view();
        ActivityTracker.Activity running = activity.begin();
        try {
            worker.execute(() -> run(pull, running));
        } catch (RuntimeException rejected) {
            running.close();
            pulls.remove(pull.id);
            throw new CapacityExceededException("pull_in_progress", "Another model is being downloaded.");
        }
        LOG.info("Model download of {} started at the user's request", model);
        return started;
    }

    private void run(Pull pull, ActivityTracker.Activity running) {
        try (running) {
            ollama.pull(pull.model, pull::progress, pull.cancellation);
            pull.finish(State.SUCCEEDED, null);
            LOG.info("Model download of {} finished", pull.model);
        } catch (CancellationException cancelled) {
            pull.finish(State.CANCELLED, null);
            LOG.info("Model download of {} was cancelled", pull.model);
        } catch (Exception failure) {
            ModelDtos.PullError error = classify(failure);
            pull.finish(State.FAILED, error);
            LOG.warn("Model download of {} failed: {} ({})", pull.model, error.code(), failure.getClass().getSimpleName());
        }
    }

    static ModelDtos.PullError classify(Exception failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (failure instanceof ModelPullException pullFailure) {
            return switch (pullFailure.reason()) {
                case NOT_FOUND -> new ModelDtos.PullError("pull_not_found", "The Ollama library has no such model.");
                case REGISTRY_UNREACHABLE -> new ModelDtos.PullError("registry_unreachable",
                        "Ollama could not reach its model library.");
                case DISK_FULL -> new ModelDtos.PullError("disk_full", "There is not enough disk space for the model.");
                case FAILED -> new ModelDtos.PullError("pull_failed", "Ollama could not download the model.");
            };
        }
        if (failure instanceof OllamaUnavailableException) {
            return new ModelDtos.PullError("ollama_unavailable", "Ollama is not reachable on this computer.");
        }
        if (failure instanceof HttpTimeoutException) {
            return new ModelDtos.PullError("ollama_timeout", "Ollama stopped reporting progress.");
        }
        if (failure instanceof RemoteModelRefusedException) {
            return new ModelDtos.PullError("remote_model_refused", "DocGen never downloads cloud models.");
        }
        return new ModelDtos.PullError("pull_failed", "Ollama could not download the model.");
    }

    synchronized ModelDtos.PullView cancel(String id) {
        Pull pull = find(id);
        if (!pull.running()) {
            throw new ConflictException("pull_finished", "The download has already finished.");
        }
        pull.cancellation.cancel();
        return pull.view();
    }

    synchronized ModelDtos.PullView get(String id) {
        return find(id).view();
    }

    /** Newest first. */
    synchronized ModelDtos.PullList list() {
        List<ModelDtos.PullView> views = new ArrayList<>();
        pulls.values().forEach(pull -> views.addFirst(pull.view()));
        return new ModelDtos.PullList(views);
    }

    synchronized boolean downloading(String model) {
        return pulls.values().stream().anyMatch(pull -> pull.running() && pull.model.equals(model));
    }

    private Pull find(String id) {
        Pull pull = pulls.get(Ids.require(id));
        if (pull == null) {
            throw new ResourceNotFoundException();
        }
        return pull;
    }

    private void trim() {
        var iterator = pulls.values().iterator();
        while (pulls.size() > MAX_KEPT && iterator.hasNext()) {
            if (!iterator.next().running()) {
                iterator.remove();
            }
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        pulls.values().stream().filter(Pull::running).forEach(pull -> pull.cancellation.cancel());
        worker.shutdownNow();
    }

    /** One download and its progress, summed over Ollama's layers. */
    private static final class Pull {
        private final String id;
        private final String model;
        private final long expectedBytes;
        private final Clock clock;
        private final Instant startedAt;
        private final Cancellation cancellation = new Cancellation();
        private final Map<String, long[]> layers = new LinkedHashMap<>();
        private final Deque<long[]> samples = new ArrayDeque<>();
        private State state = State.RUNNING;
        private PullProgress.Stage stage = PullProgress.Stage.MANIFEST;
        private long bytesPerSecond;
        private Instant finishedAt;
        private ModelDtos.PullError error;

        private Pull(String id, String model, long expectedBytes, Clock clock) {
            this.id = id;
            this.model = model;
            this.expectedBytes = expectedBytes;
            this.clock = clock;
            this.startedAt = clock.instant();
        }

        synchronized boolean running() {
            return state == State.RUNNING;
        }

        synchronized void progress(PullProgress progress) {
            if (state != State.RUNNING) {
                return;
            }
            stage = progress.stage();
            if (progress.digest() != null) {
                long[] layer = layers.computeIfAbsent(progress.digest(), digest -> new long[2]);
                if (layer[1] == 0 && progress.completedBytes() > 0) {
                    // Ollama first reports a layer without "completed", then jumps to what is already on disk
                    // when it resumes; measuring across that jump reported 668 MB/s for a resumed pull with
                    // Ollama 0.32.11. The speed is measured from the first count onwards.
                    samples.clear();
                }
                layer[0] = Math.max(layer[0], progress.totalBytes());
                layer[1] = Math.max(layer[1], progress.completedBytes());
            }
            long now = clock.millis();
            long completed = completed();
            samples.addLast(new long[]{now, completed});
            while (samples.size() > 2 && now - samples.peekFirst()[0] > SPEED_WINDOW.toMillis()) {
                samples.removeFirst();
            }
            long[] oldest = samples.peekFirst();
            long elapsed = now - oldest[0];
            if (elapsed >= 500) {
                bytesPerSecond = Math.max(0, (completed - oldest[1]) * 1000 / elapsed);
            }
        }

        synchronized void finish(State finalState, ModelDtos.PullError failure) {
            if (state != State.RUNNING) {
                return;
            }
            state = finalState;
            error = failure;
            finishedAt = clock.instant();
            bytesPerSecond = 0;
            if (finalState == State.SUCCEEDED) {
                stage = PullProgress.Stage.SUCCESS;
            }
        }

        private long completed() {
            return layers.values().stream().mapToLong(layer -> layer[1]).sum();
        }

        synchronized ModelDtos.PullView view() {
            long total = layers.values().stream().mapToLong(layer -> layer[0]).sum();
            return new ModelDtos.PullView(id, model, state, stage, completed(), total, expectedBytes,
                    bytesPerSecond, startedAt, finishedAt, error);
        }
    }
}
