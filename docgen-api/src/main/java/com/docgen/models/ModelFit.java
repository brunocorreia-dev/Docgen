package com.docgen.models;

/** How a model fits this computer, best first. */
public enum ModelFit {
    /** Fits the free GPU memory with room to spare: fast. */
    RECOMMENDED,
    /** Part runs on the GPU and part on the CPU: slower. */
    GPU_OFFLOAD,
    /** Runs on the CPU and in RAM: slow, but usable. */
    CPU_ONLY,
    /** Needs more memory than is free: it may freeze the computer or not load. */
    NOT_RECOMMENDED
}
