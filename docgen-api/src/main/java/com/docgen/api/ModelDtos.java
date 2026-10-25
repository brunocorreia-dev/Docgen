package com.docgen.api;

import com.docgen.hardware.HardwareProfile;
import com.docgen.models.CatalogModel;
import com.docgen.models.MemoryEstimate;
import com.docgen.models.ModelFit;
import com.docgen.models.ModelRecommender;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

final class ModelDtos {
    /** Same character set as {@link com.docgen.ProviderSelection#MODEL}. */
    static final String MODEL = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}";

    private ModelDtos() {
    }

    /** The Ollama version DocGen was tested against in this phase (no compatibility is claimed beyond it). */
    static final String TESTED_OLLAMA_VERSION = "0.32.11";

    record OllamaStatus(boolean reachable, String version, String address, String testedVersion) {
    }

    record HardwareView(HardwareProfile hardware, OllamaStatus ollama) {
    }

    record CatalogEntry(
            String tag,
            String family,
            long parameterCount,
            long activeParameterCount,
            String quantization,
            String downloadSize,
            long downloadBytes,
            int contextTokens,
            List<CatalogModel.Purpose> purposes,
            CatalogModel.Thinking thinking,
            boolean vision,
            String description,
            boolean verified,
            String evidence,
            ModelFit fit,
            String reason,
            Estimate estimate,
            boolean installed
    ) {
        static CatalogEntry of(ModelRecommender.Assessment assessment, boolean installed) {
            CatalogModel model = assessment.model();
            return new CatalogEntry(model.tag(), model.family(), model.parameterCount(), model.activeParameterCount(),
                    model.quantization(), model.downloadSize(), model.downloadBytes(), model.contextTokens(),
                    model.purposes(), model.thinking(), model.vision(), model.description(), model.verified(),
                    model.evidence(), assessment.fit(), assessment.reason(), Estimate.of(assessment.estimate()),
                    installed);
        }
    }

    record Estimate(int contextTokens, long weightsBytes, long kvCacheBytes, long stateBytes, long overheadBytes,
                    long totalBytes) {
        static Estimate of(MemoryEstimate estimate) {
            return new Estimate(estimate.contextTokens(), estimate.weightsBytes(), estimate.kvCacheBytes(),
                    estimate.stateBytes(), estimate.overheadBytes(), estimate.totalBytes());
        }
    }

    record Budget(long ramBytes, long gpuBytes, ModelRecommender.GpuUse gpu, List<String> notes) {
    }

    record CatalogView(
            String verifiedOn,
            int contextTokens,
            boolean ollamaReachable,
            Budget budget,
            String suggestedGeneral,
            String suggestedCode,
            List<CatalogEntry> models
    ) {
    }

    record Loaded(long sizeBytes, long vramBytes, Integer contextLength, String expiresAt) {
    }

    /**
     * @param usable DocGen may use it: in the catalog (ADR 0003, layer 1) and not remote (layer 2)
     */
    record InstalledView(
            String name,
            long sizeBytes,
            String family,
            String parameterSize,
            String quantization,
            Integer contextLength,
            List<String> capabilities,
            String modifiedAt,
            boolean inCatalog,
            boolean remote,
            boolean usable,
            boolean isDefault,
            Loaded loaded
    ) {
    }

    record InstalledList(String defaultModel, List<InstalledView> models) {
    }

    record DetailsView(
            String name,
            String architecture,
            Integer contextLength,
            String parameterSize,
            String quantization,
            String family,
            List<String> capabilities,
            boolean remote,
            boolean inCatalog
    ) {
    }

    record ModelRequest(@NotNull @Pattern(regexp = MODEL) String model) {
    }

    record DefaultModelRequest(@Pattern(regexp = MODEL) String model) {
    }

    record OnboardingRequest(boolean completed) {
    }

    record SettingsView(String defaultModel, boolean onboardingCompleted) {
    }

    record TestResult(String model, String answer, boolean cutAtLimit, Long loadMillis, Long totalMillis,
                      Double tokensPerSecond) {
    }

    record PullError(String code, String message) {
    }

    record PullView(
            String id,
            String model,
            ModelPullService.State state,
            com.docgen.PullProgress.Stage stage,
            long completedBytes,
            long totalBytes,
            long expectedBytes,
            long bytesPerSecond,
            Instant startedAt,
            Instant finishedAt,
            PullError error
    ) {
    }

    record PullList(List<PullView> pulls) {
    }
}
