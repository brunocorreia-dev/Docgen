package com.docgen.api;

import com.docgen.Cancellation;
import com.docgen.ChatMessage;
import com.docgen.ChatOutcome;
import com.docgen.ChatRequest;
import com.docgen.InstalledModel;
import com.docgen.LoadedModel;
import com.docgen.ModelDescription;
import com.docgen.OllamaApi;
import com.docgen.OllamaEndpoint;
import com.docgen.UntrustedText;
import com.docgen.hardware.HardwareProbe;
import com.docgen.hardware.HardwareProfile;
import com.docgen.models.ModelCatalog;
import com.docgen.models.ModelRecommender;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Ollama status, the hardware diagnosis, the catalog with this
 * computer's recommendations, and the installed models (list, details,
 * removal and a quick inference test).
 */
@Service
class ModelService {
    /** Small on purpose: the test only proves the model loads and answers. */
    static final int TEST_CONTEXT_TOKENS = 2_048;
    static final int TEST_OUTPUT_TOKENS = 64;
    static final int MAX_TEST_ANSWER_CHARACTERS = 300;

    private final OllamaApi ollama;
    private final HardwareProbe hardware;
    private final ModelCatalog catalog;
    private final SettingsService settings;
    private final ModelPullService pulls;
    private final ActivityTracker activity;
    private final DocGenApiProperties properties;
    private final AtomicBoolean testing = new AtomicBoolean();

    ModelService(OllamaApi ollama, HardwareProbe hardware, ModelCatalog catalog, SettingsService settings,
                 ModelPullService pulls, ActivityTracker activity, DocGenApiProperties properties) {
        this.ollama = ollama;
        this.hardware = hardware;
        this.catalog = catalog;
        this.settings = settings;
        this.pulls = pulls;
        this.activity = activity;
        this.properties = properties;
    }

    /** Never fails: an Ollama that does not answer is reported as unreachable. */
    ModelDtos.OllamaStatus status() {
        String address = OllamaEndpoint.HOST + ":" + properties.ollama().endpoint().port();
        try {
            return new ModelDtos.OllamaStatus(true, ollama.version(), address, ModelDtos.TESTED_OLLAMA_VERSION);
        } catch (Exception unreachable) {
            if (unreachable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ModelDtos.OllamaStatus(false, null, address, ModelDtos.TESTED_OLLAMA_VERSION);
        }
    }

    ModelDtos.HardwareView hardware() {
        return new ModelDtos.HardwareView(hardware.probe(), status());
    }

    /** Recommendations work without Ollama; only the "installed" marks need it. */
    ModelDtos.CatalogView catalog() {
        HardwareProfile profile = hardware.probe();
        ModelRecommender.Recommendation recommendation = ModelRecommender.recommend(catalog.models(), profile,
                ModelRecommender.DEFAULT_CONTEXT_TOKENS);
        Set<String> installed = new HashSet<>();
        boolean reachable = true;
        try {
            ollama.installedModels().stream().filter(model -> !model.remote()).forEach(model -> installed.add(model.name()));
        } catch (Exception unreachable) {
            if (unreachable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            reachable = false;
        }
        ModelRecommender.Budget budget = recommendation.budget();
        return new ModelDtos.CatalogView(catalog.verifiedOn().toString(), recommendation.contextTokens(), reachable,
                new ModelDtos.Budget(budget.ramBytes(), budget.gpuBytes(), budget.gpu(), budget.notes()),
                recommendation.suggestedGeneral(), recommendation.suggestedCode(),
                recommendation.models().stream()
                        .map(assessment -> ModelDtos.CatalogEntry.of(assessment, installed.contains(assessment.model().tag())))
                        .toList());
    }

    ModelDtos.InstalledList installed(String owner) {
        List<InstalledModel> models = call(OllamaApi::installedModels);
        Map<String, LoadedModel> loaded;
        try {
            loaded = ollama.loadedModels().stream()
                    .collect(Collectors.toMap(LoadedModel::name, Function.identity(), (first, second) -> first));
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            loaded = Map.of();
        }
        String defaultModel = settings.defaultModel(owner).orElse(null);
        Map<String, LoadedModel> inMemory = loaded;
        return new ModelDtos.InstalledList(defaultModel, models.stream().map(model -> {
            boolean inCatalog = catalog.contains(model.name());
            LoadedModel memory = inMemory.get(model.name());
            return new ModelDtos.InstalledView(model.name(), model.sizeBytes(), model.family(), model.parameterSize(),
                    model.quantization(), model.contextLength(), model.capabilities(), model.modifiedAt(), inCatalog,
                    model.remote(), inCatalog && !model.remote(), model.name().equals(defaultModel),
                    memory == null ? null : new ModelDtos.Loaded(memory.sizeBytes(), memory.vramBytes(),
                            memory.contextLength(), memory.expiresAt()));
        }).toList());
    }

    ModelDtos.DetailsView details(String name) {
        ModelDescription model = call(api -> api.describe(name));
        return new ModelDtos.DetailsView(model.name(), model.architecture(), model.contextLength(), model.parameterSize(),
                model.quantization(), model.family(), model.capabilities(), model.remote(), catalog.contains(name));
    }

    void delete(String owner, String name) {
        if (pulls.downloading(name)) {
            throw new ConflictException("model_busy", "The model is being downloaded.");
        }
        call(api -> {
            api.delete(name);
            return null;
        });
        settings.forgetDefault(owner, name);
    }

    /** Loads the model and asks for one short sentence, as the last step of the first-run guide. */
    ModelDtos.TestResult test(String model) {
        if (!catalog.contains(model)) {
            throw OllamaFailures.notInCatalog();
        }
        if (!testing.compareAndSet(false, true)) {
            throw new ConflictException("test_in_progress", "Another model test is running.");
        }
        try (ActivityTracker.Activity ignored = activity.begin()) {
            StringBuilder answer = new StringBuilder();
            ChatRequest request = new ChatRequest(model, List.of(
                    ChatMessage.system("Você é o assistente do DocGen. Responda em português do Brasil, em uma única "
                            + "frase curta."),
                    ChatMessage.user("Diga olá e confirme que está funcionando.")),
                    TEST_CONTEXT_TOKENS, TEST_OUTPUT_TOKENS, false, null);
            ChatOutcome outcome = call(api -> api.chat(request, text -> {
                if (answer.length() < MAX_TEST_ANSWER_CHARACTERS * 2) {
                    answer.append(text);
                }
            }, new Cancellation()));
            return new ModelDtos.TestResult(model, UntrustedText.block(answer.toString(), MAX_TEST_ANSWER_CHARACTERS).strip(),
                    outcome.cutAtLimit(), millis(outcome.loadDuration()), millis(outcome.totalDuration()),
                    outcome.tokensPerSecond());
        } finally {
            testing.set(false);
        }
    }

    private static Long millis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    @FunctionalInterface
    private interface OllamaCall<T> {
        T run(OllamaApi api) throws Exception;
    }

    private <T> T call(OllamaCall<T> operation) {
        try {
            return operation.run(ollama);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidRequestException();
        } catch (Exception failure) {
            throw OllamaFailures.translate(failure);
        }
    }
}
