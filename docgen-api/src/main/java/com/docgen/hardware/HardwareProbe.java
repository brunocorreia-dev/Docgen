package com.docgen.hardware;

/** Reads this computer's hardware. It never fails: what cannot be read is reported as not detected. */
@FunctionalInterface
public interface HardwareProbe {
    HardwareProfile probe();
}
