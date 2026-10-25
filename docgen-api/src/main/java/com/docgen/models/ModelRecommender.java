package com.docgen.models;

import com.docgen.hardware.HardwareProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Classifies each catalog model for this computer (ADR 0004). Pure and
 * deterministic: no I/O, and the same catalog and hardware always give the
 * same answer.
 *
 * <h2>Memory a model needs</h2>
 * <pre>
 *   weights   = downloadBytes (upper bound of the library size; includes a vision projector)
 *   KV cache  = 2 (K and V) × attentionLayers × kvHeads × headDim × 2 bytes (f16, Ollama's default) × context
 *   state     = stateBytes (fixed state of Qwen3.5's linear-attention layers)
 *   overhead  = 512 MiB (compute buffers and the GPU runtime, which /api/ps does not count)
 * </pre>
 * <p>Calibrated on 2026-10-25 with Ollama 0.32.11 and a 12 GB RTX 5070: {@code qwen2.5:14b} loaded with
 * {@code num_ctx} 4096, 8192 and 16384 took 9.47, 10.77 and 12.42 GB (10<sup>9</sup> bytes) according to
 * {@code /api/ps}, and the formula gives 10.3, 11.1 and 12.7 GB; {@code llama3.2} 3B took 2.55 and 4.06 GB at 4096 and 16384,
 * against 3.0 and 4.4 GB. It stays above the measurements, as it should. A KV cache quantized by the user
 * ({@code OLLAMA_KV_CACHE_TYPE}) makes the real figure smaller still.</p>
 *
 * <h2>Memory available</h2>
 * <ul>
 *   <li>RAM: 90% of what is available now; when that is unknown, half of the total.</li>
 *   <li>Dedicated GPU: 90% of the free memory of the best NVIDIA or AMD card whose numbers the driver
 *   confirmed. Nominal VRAM alone is never used: a card without confirmed numbers, Intel and other cards are
 *   left out and the estimate considers the CPU only.</li>
 *   <li>Apple Silicon: 90% of the smaller of two thirds of the RAM and the RAM available now. Apple does not
 *   document the share of unified memory the GPU may use; reports from users put it near 75%, and two thirds
 *   is the cautious choice.</li>
 * </ul>
 *
 * <h2>Classes</h2>
 * <ul>
 *   <li>{@link ModelFit#RECOMMENDED}: the whole model fits the GPU budget.</li>
 *   <li>{@link ModelFit#GPU_OFFLOAD}: it does not, but the GPU holds at least 30% of it and the rest fits the
 *   RAM (on Apple Silicon: it fits the RAM).</li>
 *   <li>{@link ModelFit#CPU_ONLY}: no usable GPU, or one that would hold too little, and it fits the RAM.</li>
 *   <li>{@link ModelFit#NOT_RECOMMENDED}: it does not fit.</li>
 * </ul>
 *
 * <h2>Suggested choice</h2>
 * <p>For general use and for code, separately: the best class available; within it the model with the most
 * parameters (a proxy for quality), but, below the GPU, only among models fast enough on that hardware
 * (at most 15 B active parameters with offload, 4.5 B on the CPU), else the smallest. Ties keep catalog
 * order.</p>
 */
public final class ModelRecommender {
    /** A conversation with some history; the documentation pipeline reserves 8,192 tokens for the answer alone. */
    public static final int DEFAULT_CONTEXT_TOKENS = 8_192;
    static final long MIB = 1024L * 1024;
    static final long GIB = 1024L * MIB;
    static final long RUNTIME_OVERHEAD_BYTES = 512 * MIB;
    static final int KV_BYTES_PER_ELEMENT = 2;
    static final double HEADROOM = 0.90;
    static final double UNKNOWN_AVAILABLE_RAM_SHARE = 0.5;
    static final double APPLE_GPU_SHARE = 2.0 / 3;
    static final long MIN_USEFUL_GPU_BYTES = GIB;
    static final double MIN_GPU_SHARE_FOR_OFFLOAD = 0.30;
    static final long OFFLOAD_SPEED_LIMIT_PARAMETERS = 15_000_000_000L;
    static final long CPU_SPEED_LIMIT_PARAMETERS = 4_500_000_000L;

    private ModelRecommender() {
    }

    public enum GpuUse { NONE, DEDICATED, UNIFIED }

    /**
     * What this computer offers to a model.
     *
     * @param gpuBytes 0 when no GPU is planned with
     * @param notes    plain-language caveats for the screen, in Portuguese
     */
    public record Budget(long ramBytes, long gpuBytes, GpuUse gpu, List<String> notes) {
        public Budget {
            notes = List.copyOf(notes);
        }
    }

    public record Assessment(CatalogModel model, ModelFit fit, MemoryEstimate estimate, String reason) {
    }

    public record Recommendation(
            int contextTokens,
            Budget budget,
            List<Assessment> models,
            String suggestedGeneral,
            String suggestedCode
    ) {
        public Recommendation {
            models = List.copyOf(models);
        }
    }

    public static Recommendation recommend(List<CatalogModel> catalog, HardwareProfile profile, int contextTokens) {
        Budget budget = budget(profile);
        List<Assessment> assessments = catalog.stream().map(model -> assess(model, budget, contextTokens)).toList();
        return new Recommendation(contextTokens, budget, assessments,
                suggest(assessments, CatalogModel.Purpose.GENERAL), suggest(assessments, CatalogModel.Purpose.CODE));
    }

    public static MemoryEstimate estimate(CatalogModel model, int contextTokens) {
        int context = Math.min(contextTokens, model.contextTokens());
        CatalogModel.Architecture architecture = model.architecture();
        long kvCache = 2L * architecture.attentionLayers() * architecture.kvHeads() * architecture.headDim()
                * KV_BYTES_PER_ELEMENT * context;
        return new MemoryEstimate(context, model.downloadBytes(), kvCache, architecture.stateBytes(),
                RUNTIME_OVERHEAD_BYTES);
    }

    public static Budget budget(HardwareProfile profile) {
        List<String> notes = new ArrayList<>();
        Long total = profile.ramTotalBytes();
        Long available = profile.ramAvailableBytes();
        long ram;
        if (available != null) {
            ram = share(available, HEADROOM);
        } else if (total != null) {
            ram = share(total, UNKNOWN_AVAILABLE_RAM_SHARE);
            notes.add("A memória livre não foi detectada: a estimativa considera metade da RAM total.");
        } else {
            ram = 0;
            notes.add("A memória RAM não foi detectada, então nenhum modelo pode ser recomendado com segurança.");
        }
        if (profile.unifiedMemory()) {
            if (total == null) {
                return new Budget(ram, 0, GpuUse.NONE, notes);
            }
            long gpuShare = share(total, APPLE_GPU_SHARE);
            long usable = available == null ? gpuShare : Math.min(gpuShare, available);
            notes.add("Mac com Apple Silicon: a GPU usa a mesma memória do sistema (memória unificada). "
                    + "O DocGen conta com até dois terços dela para a GPU.");
            return new Budget(ram, share(usable, HEADROOM), GpuUse.UNIFIED, notes);
        }
        Optional<HardwareProfile.Gpu> best = profile.gpus().stream()
                .filter(gpu -> gpu.kind() == HardwareProfile.Kind.NVIDIA || gpu.kind() == HardwareProfile.Kind.AMD)
                .filter(gpu -> gpu.vramConfirmed() && gpu.vramFreeBytes() != null)
                .max(Comparator.comparingLong(HardwareProfile.Gpu::vramFreeBytes));
        if (best.isPresent()) {
            long gpu = share(best.get().vramFreeBytes(), HEADROOM);
            if (gpu >= MIN_USEFUL_GPU_BYTES) {
                return new Budget(ram, gpu, GpuUse.DEDICATED, notes);
            }
            notes.add("A placa de vídeo tem pouca memória livre para modelos: a estimativa considera só a CPU.");
            return new Budget(ram, 0, GpuUse.NONE, notes);
        }
        boolean unconfirmed = profile.gpus().stream().anyMatch(gpu ->
                gpu.kind() == HardwareProfile.Kind.NVIDIA || gpu.kind() == HardwareProfile.Kind.AMD);
        if (unconfirmed) {
            notes.add("A memória da placa de vídeo não foi confirmada pelo driver, então a estimativa considera "
                    + "só a CPU. Com o driver oficial da placa instalado, o DocGen consegue medir.");
        } else if (!profile.gpus().isEmpty()) {
            notes.add("Placas de vídeo integradas não entram nesta estimativa, que considera só a CPU.");
        }
        return new Budget(ram, 0, GpuUse.NONE, notes);
    }

    public static Assessment assess(CatalogModel model, Budget budget, int contextTokens) {
        MemoryEstimate estimate = estimate(model, contextTokens);
        long need = estimate.totalBytes();
        String needed = size(need);
        return switch (budget.gpu()) {
            case UNIFIED -> {
                if (need <= budget.gpuBytes()) {
                    yield new Assessment(model, ModelFit.RECOMMENDED, estimate, "Cabe na memória que o Mac reserva "
                            + "para a GPU: precisa de cerca de " + needed + " de " + size(budget.gpuBytes())
                            + " disponíveis. Deve responder rápido.");
                }
                if (need <= budget.ramBytes()) {
                    yield new Assessment(model, ModelFit.GPU_OFFLOAD, estimate, "Passa do que o Mac reserva para a "
                            + "GPU (precisa de cerca de " + needed + "; a GPU fica com até " + size(budget.gpuBytes())
                            + "): parte roda na CPU, mais devagar.");
                }
                yield notRecommended(model, estimate, budget);
            }
            case DEDICATED -> {
                if (need <= budget.gpuBytes()) {
                    yield new Assessment(model, ModelFit.RECOMMENDED, estimate, "Cabe inteiro na placa de vídeo: "
                            + "precisa de cerca de " + needed + " e há " + size(budget.gpuBytes())
                            + " livres para ele. Deve responder rápido.");
                }
                if (budget.gpuBytes() >= need * MIN_GPU_SHARE_FOR_OFFLOAD
                        && need - budget.gpuBytes() <= budget.ramBytes()) {
                    yield new Assessment(model, ModelFit.GPU_OFFLOAD, estimate, "Não cabe inteiro na placa de vídeo "
                            + "(precisa de cerca de " + needed + "; há " + size(budget.gpuBytes())
                            + " livres): parte roda na CPU, mais devagar.");
                }
                if (need <= budget.ramBytes()) {
                    yield cpuOnly(model, estimate, "A placa de vídeo comportaria pouco dele (precisa de cerca de "
                            + needed + "; há " + size(budget.gpuBytes()) + " livres na placa): roda quase todo na "
                            + "CPU, usando a RAM. Funciona, mas devagar.");
                }
                yield notRecommended(model, estimate, budget);
            }
            case NONE -> need <= budget.ramBytes()
                    ? cpuOnly(model, estimate, "Sem placa de vídeo para o modelo: roda na CPU, usando cerca de "
                    + needed + " dos " + size(budget.ramBytes()) + " de RAM livres. Funciona, mas devagar.")
                    : notRecommended(model, estimate, budget);
        };
    }

    private static Assessment cpuOnly(CatalogModel model, MemoryEstimate estimate, String reason) {
        String slow = model.activeParameterCount() > CPU_SPEED_LIMIT_PARAMETERS
                ? " Com este tamanho, só na CPU, as respostas ficam bem lentas." : "";
        return new Assessment(model, ModelFit.CPU_ONLY, estimate, reason + slow);
    }

    private static Assessment notRecommended(CatalogModel model, MemoryEstimate estimate, Budget budget) {
        long free = budget.gpu() == GpuUse.DEDICATED ? budget.ramBytes() + budget.gpuBytes() : budget.ramBytes();
        return new Assessment(model, ModelFit.NOT_RECOMMENDED, estimate, "Precisa de cerca de "
                + size(estimate.totalBytes()) + " de memória, mais do que este computador tem livre agora ("
                + size(free) + "). Pode deixar tudo lento ou nem abrir. Fechar outros programas libera memória.");
    }

    static String suggest(List<Assessment> assessments, CatalogModel.Purpose purpose) {
        for (ModelFit fit : List.of(ModelFit.RECOMMENDED, ModelFit.GPU_OFFLOAD, ModelFit.CPU_ONLY)) {
            List<Assessment> candidates = assessments.stream()
                    .filter(assessment -> assessment.fit() == fit && assessment.model().purposes().contains(purpose))
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            long speedLimit = switch (fit) {
                case GPU_OFFLOAD -> OFFLOAD_SPEED_LIMIT_PARAMETERS;
                case CPU_ONLY -> CPU_SPEED_LIMIT_PARAMETERS;
                default -> Long.MAX_VALUE;
            };
            Assessment chosen = null;
            for (Assessment candidate : candidates) {
                boolean fastEnough = candidate.model().activeParameterCount() <= speedLimit;
                if (fastEnough && (chosen == null
                        || candidate.model().parameterCount() > chosen.model().parameterCount())) {
                    chosen = candidate;
                }
            }
            if (chosen == null) {
                chosen = candidates.stream()
                        .min(Comparator.comparingLong(assessment -> assessment.estimate().totalBytes()))
                        .orElseThrow();
            }
            return chosen.model().tag();
        }
        return null;
    }

    private static long share(long bytes, double fraction) {
        return (long) Math.floor(bytes * fraction);
    }

    /** Binary sizes with the decimal comma, as the system's task manager shows them ("7,0 GB", "512 MB"). */
    static String size(long bytes) {
        if (bytes < GIB) {
            return String.format(Locale.ROOT, "%d MB", Math.round(bytes / (double) MIB));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (double) GIB).replace('.', ',');
    }
}
