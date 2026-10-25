package com.docgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Client of the local Ollama API for the desktop app. The address is the
 * fixed loopback endpoint ({@link OllamaEndpoint}); requests go through no
 * proxy, follow no redirect, have a deadline and accept a bounded body.
 * Response text never reaches an exception message.
 *
 * <p>Formats verified on 2026-10-25 against the official API documentation
 * (github.com/ollama/ollama, {@code docs/api.md}), the Ollama source
 * ({@code api/types.go}) and Ollama 0.32.11; see the test fixtures.</p>
 */
public final class OllamaClient implements OllamaApi {
    static final int MAX_SMALL_RESPONSE_BYTES = 64 * 1024;
    static final int MAX_LIST_RESPONSE_BYTES = 4 * 1024 * 1024;
    static final int MAX_SHOW_RESPONSE_BYTES = 4 * 1024 * 1024;
    static final int MAX_MODELS = 1_000;
    static final int MAX_LABEL_CHARACTERS = 64;
    static final int MAX_REPORTED_CONTEXT_TOKENS = 16 * 1024 * 1024;
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(15);
    /** Releases such as {@code 0.32.11} or {@code 0.12.0-rc1}. */
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,4}(\\.[0-9]{1,6}){0,3}(-[0-9A-Za-z.]{1,32})?");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{12,64}");

    private final OllamaEndpoint endpoint;
    private final HttpClient client;

    public OllamaClient(OllamaEndpoint endpoint) {
        this(endpoint, directClient());
    }

    OllamaClient(OllamaEndpoint endpoint, HttpClient client) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** The only way DocGen builds an HTTP client for Ollama: short connect timeout, no proxy, no redirects. */
    static HttpClient directClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .proxy(DirectProxySelector.INSTANCE)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    OllamaEndpoint endpoint() {
        return endpoint;
    }

    @Override
    public String version() throws IOException, InterruptedException {
        JsonNode root = OllamaJson.object(get("version", MAX_SMALL_RESPONSE_BYTES), "version");
        String version = OllamaJson.text(root, "version", 48, "version");
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IOException("Ollama returned an invalid version.");
        }
        return version;
    }

    @Override
    public List<InstalledModel> installedModels() throws IOException, InterruptedException {
        return parseInstalledModels(get("tags", MAX_LIST_RESPONSE_BYTES));
    }

    @Override
    public ModelDescription describe(String model) throws IOException, InterruptedException {
        String name = requireModelName(model);
        HttpResponse<String> response = send(post("show", modelBody(name)), MAX_SHOW_RESPONSE_BYTES);
        if (response.statusCode() == 404) {
            throw new ModelNotInstalledException(name);
        }
        requireSuccess(response, "show");
        return parseDescription(response.body(), name);
    }

    @Override
    public List<LoadedModel> loadedModels() throws IOException, InterruptedException {
        return parseLoadedModels(get("ps", MAX_LIST_RESPONSE_BYTES));
    }

    @Override
    public void delete(String model) throws IOException, InterruptedException {
        String name = requireModelName(model);
        HttpRequest request = HttpRequest.newBuilder(endpoint.api("delete"))
                .timeout(REQUEST_DEADLINE)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(modelBody(name)))
                .build();
        HttpResponse<String> response = send(request, MAX_SMALL_RESPONSE_BYTES);
        if (response.statusCode() == 404) {
            throw new ModelNotInstalledException(name);
        }
        requireSuccess(response, "delete");
    }

    /** Model names are restricted like everywhere else in DocGen (see {@link ProviderSelection#MODEL}). */
    static String requireModelName(String model) {
        if (model == null || !ProviderSelection.MODEL.matcher(model).matches()) {
            throw new IllegalArgumentException("Invalid model name");
        }
        return model;
    }

    static List<InstalledModel> parseInstalledModels(String body) throws IOException {
        JsonNode models = OllamaJson.object(body, "model list").path("models");
        if (!models.isArray()) {
            throw new IOException("Ollama returned an invalid model list.");
        }
        if (models.size() > MAX_MODELS) {
            throw new IOException("Ollama listed more models than DocGen accepts.");
        }
        List<InstalledModel> installed = new ArrayList<>();
        for (JsonNode entry : models) {
            if (!entry.isObject()) {
                throw new IOException("Ollama returned an invalid model list.");
            }
            JsonNode name = entry.path("name");
            if (!name.isTextual() || !ProviderSelection.MODEL.matcher(name.asText()).matches()) {
                // DocGen cannot address a model it cannot name safely, so it does not list it.
                continue;
            }
            JsonNode details = entry.path("details");
            String digest = OllamaJson.text(entry, "digest", 64, "model list");
            installed.add(new InstalledModel(
                    name.asText(),
                    OllamaJson.nonNegativeLong(entry, "size", "model list"),
                    digest != null && DIGEST.matcher(digest).matches() ? digest : null,
                    OllamaJson.text(details, "family", MAX_LABEL_CHARACTERS, "model list"),
                    OllamaJson.text(details, "parameter_size", MAX_LABEL_CHARACTERS, "model list"),
                    OllamaJson.text(details, "quantization_level", MAX_LABEL_CHARACTERS, "model list"),
                    OllamaJson.boundedInt(details.path("context_length"), 1, MAX_REPORTED_CONTEXT_TOKENS),
                    OllamaJson.capabilities(entry),
                    OllamaJson.text(entry, "modified_at", MAX_LABEL_CHARACTERS, "model list"),
                    RemoteModels.declaresRemote(entry) || RemoteModels.isCloudName(name.asText())));
        }
        return List.copyOf(installed);
    }

    static ModelDescription parseDescription(String body, String name) throws IOException {
        JsonNode root = OllamaJson.object(body, "model description");
        JsonNode details = root.path("details");
        JsonNode info = root.path("model_info");
        String architecture = OllamaJson.architecture(info);
        Integer contextLength = null;
        Integer layers = null;
        Integer kvHeads = null;
        Integer headDim = null;
        if (architecture != null) {
            contextLength = OllamaJson.boundedInt(info.path(architecture + ".context_length"), 1,
                    MAX_REPORTED_CONTEXT_TOKENS);
            layers = OllamaJson.boundedInt(info.path(architecture + ".block_count"), 1, 4_096);
            kvHeads = OllamaJson.boundedInt(info.path(architecture + ".attention.head_count_kv"), 1, 4_096);
            headDim = OllamaJson.boundedInt(info.path(architecture + ".attention.key_length"), 1, 65_536);
            Integer embedding = OllamaJson.boundedInt(info.path(architecture + ".embedding_length"), 1, 1 << 20);
            Integer heads = OllamaJson.boundedInt(info.path(architecture + ".attention.head_count"), 1, 4_096);
            if (headDim == null && embedding != null && heads != null && embedding % heads == 0) {
                headDim = embedding / heads;
            }
        }
        return new ModelDescription(
                name,
                architecture,
                contextLength,
                OllamaJson.boundedLong(info.path("general.parameter_count"), 10_000_000_000_000L),
                OllamaJson.text(details, "parameter_size", MAX_LABEL_CHARACTERS, "model description"),
                OllamaJson.text(details, "quantization_level", MAX_LABEL_CHARACTERS, "model description"),
                OllamaJson.text(details, "family", MAX_LABEL_CHARACTERS, "model description"),
                OllamaJson.capabilities(root),
                layers,
                kvHeads,
                headDim,
                RemoteModels.declaresRemote(root) || RemoteModels.isCloudName(name));
    }

    static List<LoadedModel> parseLoadedModels(String body) throws IOException {
        JsonNode models = OllamaJson.object(body, "loaded model list").path("models");
        if (!models.isArray() || models.size() > MAX_MODELS) {
            throw new IOException("Ollama returned an invalid loaded model list.");
        }
        List<LoadedModel> loaded = new ArrayList<>();
        for (JsonNode entry : models) {
            if (!entry.isObject()) {
                throw new IOException("Ollama returned an invalid loaded model list.");
            }
            JsonNode name = entry.path("name");
            if (!name.isTextual() || !ProviderSelection.MODEL.matcher(name.asText()).matches()) {
                continue;
            }
            long size = OllamaJson.nonNegativeLong(entry, "size", "loaded model list");
            long vram = OllamaJson.nonNegativeLong(entry, "size_vram", "loaded model list");
            loaded.add(new LoadedModel(name.asText(), size, Math.min(vram, size),
                    OllamaJson.boundedInt(entry.path("context_length"), 1, MAX_REPORTED_CONTEXT_TOKENS),
                    OllamaJson.text(entry, "expires_at", MAX_LABEL_CHARACTERS, "loaded model list")));
        }
        return List.copyOf(loaded);
    }

    private String get(String operation, int maxBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint.api(operation))
                .timeout(REQUEST_DEADLINE)
                .GET()
                .build();
        HttpResponse<String> response = send(request, maxBytes);
        requireSuccess(response, operation);
        return response.body();
    }

    HttpRequest post(String operation, String json) {
        return HttpRequest.newBuilder(endpoint.api(operation))
                .timeout(REQUEST_DEADLINE)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request, int maxBytes) throws IOException, InterruptedException {
        return HttpExchange.send(client, request, info -> successful(info.statusCode())
                ? BoundedUtf8BodySubscriber.of(maxBytes, "Ollama")
                : HttpResponse.BodySubscribers.replacing(""), REQUEST_DEADLINE, "Ollama");
    }

    private static void requireSuccess(HttpResponse<?> response, String operation) throws IOException {
        if (!successful(response.statusCode())) {
            throw new IOException("Ollama answered " + operation + " with HTTP " + response.statusCode() + ".");
        }
    }

    static boolean successful(int status) {
        return status >= 200 && status < 300;
    }

    static String modelBody(String model) throws IOException {
        ObjectNode body = OllamaJson.MAPPER.createObjectNode();
        body.put("model", model);
        return OllamaJson.MAPPER.writeValueAsString(body);
    }
}
