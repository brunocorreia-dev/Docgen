package com.docgen.models;

import com.docgen.hardware.HardwareProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recommender against the fictitious machines of the request (section 5.3), with the real catalog. */
class ModelRecommenderTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final ModelCatalog CATALOG = ModelCatalog.load();

    static HardwareProfile machine(double ramTotalGib, double ramAvailableGib, List<HardwareProfile.Gpu> gpus,
                                   boolean unified) {
        return new HardwareProfile("Linux", "test", unified ? "aarch64" : "x86_64", "CPU", 8, 16,
                (long) (ramTotalGib * GIB), (long) (ramAvailableGib * GIB), gpus, unified,
                new HardwareProfile.Storage("/models", 200 * GIB, true));
    }

    static HardwareProfile.Gpu nvidia(String name, double totalGib, double freeGib) {
        return new HardwareProfile.Gpu("NVIDIA", name, (long) (totalGib * GIB), (long) (freeGib * GIB), true, "driver da placa");
    }

    static final HardwareProfile CPU_ONLY_8GB = machine(8, 5.5,
            List.of(new HardwareProfile.Gpu("Intel", "UHD Graphics 620", null, null, false, null)), false);
    static final HardwareProfile GPU_6GB = machine(16, 11, List.of(nvidia("GeForce RTX 2060", 6, 5.6)), false);
    static final HardwareProfile GPU_12GB = machine(32, 24, List.of(nvidia("GeForce RTX 3060", 12, 11.2)), false);
    static final HardwareProfile APPLE_16GB = machine(16, 10,
            List.of(new HardwareProfile.Gpu("Apple", "Apple M2", null, null, false, "memória unificada")), true);
    static final HardwareProfile TINY_4GB = machine(4, 2.5, List.of(), false);

    private static Map<String, ModelFit> fits(HardwareProfile profile) {
        return recommend(profile).models().stream()
                .collect(Collectors.toMap(assessment -> assessment.model().tag(), ModelRecommender.Assessment::fit));
    }

    private static ModelRecommender.Recommendation recommend(HardwareProfile profile) {
        return ModelRecommender.recommend(CATALOG.models(), profile, ModelRecommender.DEFAULT_CONTEXT_TOKENS);
    }

    @Test
    void aCpuOnlyNotebookWith8GbRunsSmallModelsOnTheCpu() {
        Map<String, ModelFit> fits = fits(CPU_ONLY_8GB);
        ModelRecommender.Recommendation recommendation = recommend(CPU_ONLY_8GB);

        assertEquals(ModelFit.CPU_ONLY, fits.get("qwen3:4b-instruct"));
        assertEquals(ModelFit.CPU_ONLY, fits.get("qwen2.5-coder:3b"));
        assertEquals(ModelFit.NOT_RECOMMENDED, fits.get("qwen3:8b"));
        assertEquals(ModelFit.NOT_RECOMMENDED, fits.get("qwen2.5-coder:7b"));
        assertTrue(fits.values().stream().noneMatch(fit -> fit == ModelFit.RECOMMENDED || fit == ModelFit.GPU_OFFLOAD));
        assertEquals("qwen3.5:4b", recommendation.suggestedGeneral());
        assertEquals("qwen2.5-coder:3b", recommendation.suggestedCode());
        assertTrue(recommendation.budget().notes().stream().anyMatch(note -> note.contains("integradas")));
    }

    @Test
    void aGpuWith6GbHoldsModelsUpTo4BAndOffloadsThe7BOnes() {
        Map<String, ModelFit> fits = fits(GPU_6GB);
        ModelRecommender.Recommendation recommendation = recommend(GPU_6GB);

        assertEquals(ModelFit.RECOMMENDED, fits.get("qwen3:4b-instruct"));
        assertEquals(ModelFit.RECOMMENDED, fits.get("qwen2.5-coder:3b"));
        assertEquals(ModelFit.GPU_OFFLOAD, fits.get("qwen3:8b"));
        assertEquals(ModelFit.GPU_OFFLOAD, fits.get("qwen2.5-coder:7b"));
        assertEquals(ModelFit.NOT_RECOMMENDED, fits.get("qwen3:32b"));
        assertEquals("qwen3.5:4b", recommendation.suggestedGeneral());
        assertEquals("qwen2.5-coder:3b", recommendation.suggestedCode());
    }

    @Test
    void aGpuWith12GbHoldsThe8BAnd9BModelsButNotThe14BOnesAt8kContext() {
        Map<String, ModelFit> fits = fits(GPU_12GB);
        ModelRecommender.Recommendation recommendation = recommend(GPU_12GB);

        assertEquals(ModelFit.RECOMMENDED, fits.get("qwen3:8b"));
        assertEquals(ModelFit.RECOMMENDED, fits.get("qwen3.5:9b"));
        assertEquals(ModelFit.RECOMMENDED, fits.get("qwen2.5-coder:7b"));
        // Matches the measurement: qwen2.5:14b at num_ctx 8192 was partly offloaded on a 12 GB card.
        assertEquals(ModelFit.GPU_OFFLOAD, fits.get("qwen3:14b"));
        assertEquals(ModelFit.GPU_OFFLOAD, fits.get("qwen2.5-coder:14b"));
        assertEquals(ModelFit.GPU_OFFLOAD, fits.get("qwen3-coder:30b"));
        assertEquals("qwen3.5:9b", recommendation.suggestedGeneral());
        assertEquals("qwen2.5-coder:7b", recommendation.suggestedCode());
    }

    @Test
    void appleSiliconWith16GbPlansWithTwoThirdsOfUnifiedMemory() {
        Map<String, ModelFit> fits = fits(APPLE_16GB);
        ModelRecommender.Recommendation recommendation = recommend(APPLE_16GB);

        assertEquals(ModelRecommender.GpuUse.UNIFIED, recommendation.budget().gpu());
        assertEquals(ModelFit.RECOMMENDED, fits.get("qwen3:8b"));
        assertEquals(ModelFit.RECOMMENDED, fits.get("qwen2.5-coder:7b"));
        assertEquals(ModelFit.NOT_RECOMMENDED, fits.get("qwen3:14b"));
        assertTrue(fits.values().stream().noneMatch(fit -> fit == ModelFit.CPU_ONLY), "unified memory: never CPU only");
        assertEquals("qwen3.5:9b", recommendation.suggestedGeneral());
        assertEquals("qwen2.5-coder:7b", recommendation.suggestedCode());
        assertTrue(recommendation.budget().notes().stream().anyMatch(note -> note.contains("memória unificada")));
        assertTrue(recommendation.budget().notes().stream().anyMatch(note ->
                note.contains("dois terços") && note.contains("estimativa não verificada")
                        && note.contains("não uma medição")));
    }

    @Test
    void appleSiliconWithMoreFreeRamThanTheGpuShareOffloads() {
        HardwareProfile roomy = machine(16, 14, APPLE_16GB.gpus(), true);

        assertEquals(ModelFit.GPU_OFFLOAD, fits(roomy).get("qwen3:14b"));
    }

    @Test
    void aMachineWith4GbOnlyGetsTheSmallestModels() {
        Map<String, ModelFit> fits = fits(TINY_4GB);
        ModelRecommender.Recommendation recommendation = recommend(TINY_4GB);

        assertEquals(ModelFit.CPU_ONLY, fits.get("qwen3:0.6b"));
        assertEquals(ModelFit.CPU_ONLY, fits.get("qwen2.5-coder:1.5b"));
        assertEquals(ModelFit.NOT_RECOMMENDED, fits.get("qwen3:1.7b"));
        assertEquals(14, fits.values().stream().filter(fit -> fit == ModelFit.NOT_RECOMMENDED).count());
        assertEquals("qwen3:0.6b", recommendation.suggestedGeneral());
        assertEquals("qwen2.5-coder:1.5b", recommendation.suggestedCode());
    }

    @Test
    void nominalVramThatTheDriverDidNotConfirmIsNeverPlannedWith() {
        HardwareProfile unconfirmed = machine(32, 24, List.of(new HardwareProfile.Gpu("NVIDIA", "GeForce RTX 3060",
                12 * GIB, null, false, "sistema, não confirmado")), false);
        ModelRecommender.Recommendation recommendation = recommend(unconfirmed);

        assertEquals(ModelRecommender.GpuUse.NONE, recommendation.budget().gpu());
        assertTrue(recommendation.models().stream().noneMatch(assessment -> assessment.fit() == ModelFit.RECOMMENDED));
        assertTrue(recommendation.budget().notes().getFirst().contains("não foi confirmada"));
    }

    @Test
    void aGpuWithLittleFreeMemoryIsLeftOut() {
        HardwareProfile busy = machine(16, 12, List.of(nvidia("GeForce GTX 1050", 2, 0.5)), false);

        assertEquals(ModelRecommender.GpuUse.NONE, recommend(busy).budget().gpu());
    }

    @Test
    void aGpuThatWouldHoldTooLittleRunsTheModelOnTheCpu() {
        HardwareProfile smallGpu = machine(64, 60, List.of(nvidia("GeForce GTX 1650", 4, 3.8)), false);
        ModelRecommender.Assessment big = recommend(smallGpu).models().stream()
                .filter(assessment -> assessment.model().tag().equals("qwen3:32b")).findFirst().orElseThrow();

        assertEquals(ModelFit.CPU_ONLY, big.fit(), "3.4 GB of a 21.6 GB model is under the 30% offload share");
        assertTrue(big.reason().contains("bem lentas"), big.reason());
    }

    @Test
    void unknownFreeRamCountsHalfOfTheTotal() {
        HardwareProfile profile = new HardwareProfile("Linux", null, "x86_64", null, null, null, 8 * GIB, null,
                List.of(), false, new HardwareProfile.Storage(null, null, false));
        ModelRecommender.Budget budget = ModelRecommender.budget(profile);

        assertEquals(4 * GIB, budget.ramBytes());
        assertTrue(budget.notes().getFirst().contains("metade"));
    }

    @Test
    void nothingIsSuggestedWhenNothingFits() {
        HardwareProfile nothing = machine(1, 0.5, List.of(), false);
        ModelRecommender.Recommendation recommendation = recommend(nothing);

        assertNull(recommendation.suggestedGeneral());
        assertNull(recommendation.suggestedCode());
    }

    @Test
    void theEstimateFollowsTheDocumentedFormula() {
        CatalogModel coder = CATALOG.find("qwen2.5-coder:7b").orElseThrow();
        MemoryEstimate estimate = ModelRecommender.estimate(coder, 8_192);

        assertEquals(4_750_000_000L, estimate.weightsBytes());
        assertEquals(2L * 28 * 4 * 128 * 2 * 8_192, estimate.kvCacheBytes());
        assertEquals(512L * 1024 * 1024, estimate.overheadBytes());
        assertEquals(5_756_632_960L, estimate.totalBytes());

        CatalogModel hybrid = CATALOG.find("qwen3.5:9b").orElseThrow();
        MemoryEstimate qwen35 = ModelRecommender.estimate(hybrid, 8_192);
        assertEquals(2L * 8 * 4 * 256 * 2 * 8_192, qwen35.kvCacheBytes(), "only the full-attention layers keep a KV cache");
        assertEquals(53_000_000L, qwen35.stateBytes());

        assertEquals(32_768, ModelRecommender.estimate(coder, 1_000_000).contextTokens(), "capped at the model's window");
    }

    @Test
    void theEstimateStaysAboveTheMeasuredLoad() {
        // qwen2.5:14b (8,988,124,069 bytes, 48 layers, 8 KV heads, head 128) measured with Ollama 0.32.11.
        CatalogModel measured = new CatalogModel("qwen2.5:14b", "qwen2", 14_770_033_664L, 14_770_033_664L, "Q4_K_M",
                "9.0 GB", 8_988_124_069L, 32_768, new CatalogModel.Architecture(48, 48, 8, 128, 0),
                List.of(CatalogModel.Purpose.GENERAL), CatalogModel.Thinking.NONE, false, "x", true, "measured");
        long[][] contextAndMeasuredBytes = {{4_096, 9_470_098_799L}, {8_192, 10_765_394_245L}, {16_384, 12_421_127_539L}};
        for (long[] sample : contextAndMeasuredBytes) {
            long estimated = ModelRecommender.estimate(measured, (int) sample[0]).totalBytes();
            assertTrue(estimated >= sample[1], sample[0] + " tokens: " + estimated + " < measured " + sample[1]);
            assertTrue(estimated - sample[1] < GIB, "conservative, but not absurdly so");
        }
    }

    @Test
    void reasonsExplainTheNumbersInPlainPortuguese() {
        ModelRecommender.Assessment offload = recommend(GPU_12GB).models().stream()
                .filter(assessment -> assessment.model().tag().equals("qwen3:14b")).findFirst().orElseThrow();
        assertEquals("Não cabe inteiro na placa de vídeo (precisa de cerca de 10,5 GB; há 10,1 GB livres): "
                + "parte roda na CPU, mais devagar.", offload.reason());

        ModelRecommender.Assessment notRecommended = recommend(TINY_4GB).models().stream()
                .filter(assessment -> assessment.model().tag().equals("qwen3:8b")).findFirst().orElseThrow();
        assertTrue(notRecommended.reason().startsWith("Precisa de cerca de 6,5 GB de memória"), notRecommended.reason());
        assertEquals("512 MB", ModelRecommender.size(512L * 1024 * 1024));
    }

    @Test
    void theSameInputAlwaysGivesTheSameAnswer() {
        assertEquals(recommend(GPU_6GB), recommend(GPU_6GB));
        assertEquals(recommend(APPLE_16GB), recommend(APPLE_16GB));
    }
}
