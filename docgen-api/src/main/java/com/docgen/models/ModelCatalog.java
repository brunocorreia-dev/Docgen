package com.docgen.models;

import com.docgen.RemoteModels;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The versioned Qwen catalog, the single source of model data
 * ({@code models/qwen-catalog.json}). It is also the allowlist of ADR 0003:
 * the app downloads and uses only these tags. The file is checked when it is
 * loaded, so a broken entry stops the app at startup instead of misleading a
 * recommendation.
 */
public final class ModelCatalog {
    public static final String RESOURCE = "/models/qwen-catalog.json";
    static final int SCHEMA_VERSION = 1;
    /** Library tags only: no registry host or namespace, so a download can only come from Ollama's library. */
    private static final Pattern TAG = Pattern.compile("[a-z0-9][a-z0-9._-]{0,40}:[a-z0-9][a-z0-9._-]{0,40}");

    private final LocalDate verifiedOn;
    private final List<CatalogModel> models;

    private record Document(int schemaVersion, LocalDate verifiedOn, List<String> notes, List<CatalogModel> models) {
    }

    private ModelCatalog(LocalDate verifiedOn, List<CatalogModel> models) {
        this.verifiedOn = verifiedOn;
        this.models = List.copyOf(models);
    }

    public static ModelCatalog load() {
        try (InputStream stream = ModelCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("The model catalog is missing");
            }
            return parse(stream.readAllBytes());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("The model catalog could not be read", unreadable);
        }
    }

    static ModelCatalog parse(byte[] json) {
        JsonMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .build();
        Document document = mapper.readValue(json, Document.class);
        if (document.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported model catalog version " + document.schemaVersion());
        }
        if (document.verifiedOn() == null || document.models() == null || document.models().isEmpty()) {
            throw new IllegalStateException("The model catalog is incomplete");
        }
        Set<String> tags = new HashSet<>();
        for (CatalogModel model : document.models()) {
            validate(model);
            if (!tags.add(model.tag())) {
                throw new IllegalStateException("Duplicate catalog tag " + model.tag());
            }
        }
        return new ModelCatalog(document.verifiedOn(), document.models());
    }

    private static void validate(CatalogModel model) {
        String tag = model.tag();
        if (tag == null || !TAG.matcher(tag).matches() || RemoteModels.isCloudName(tag)) {
            throw new IllegalStateException("Invalid catalog tag " + tag);
        }
        CatalogModel.Architecture architecture = model.architecture();
        boolean valid = model.family() != null && !model.family().isBlank()
                && model.parameterCount() > 0
                && model.activeParameterCount() > 0 && model.activeParameterCount() <= model.parameterCount()
                && model.quantization() != null && !model.quantization().isBlank()
                && model.downloadSize() != null && !model.downloadSize().isBlank()
                && model.downloadBytes() > 0
                && model.contextTokens() >= 2_048
                && architecture != null && architecture.layers() > 0
                && architecture.attentionLayers() > 0 && architecture.attentionLayers() <= architecture.layers()
                && architecture.kvHeads() > 0 && architecture.headDim() > 0 && architecture.stateBytes() >= 0
                && !model.purposes().isEmpty() && model.thinking() != null
                && model.description() != null && !model.description().isBlank() && model.description().length() <= 300
                && model.evidence() != null && !model.evidence().isBlank();
        if (!valid) {
            throw new IllegalStateException("Incomplete catalog entry " + tag);
        }
    }

    public LocalDate verifiedOn() {
        return verifiedOn;
    }

    public List<CatalogModel> models() {
        return models;
    }

    public Optional<CatalogModel> find(String tag) {
        return models.stream().filter(model -> model.tag().equals(tag)).findFirst();
    }

    public boolean contains(String tag) {
        return find(tag).isPresent();
    }
}
