package com.docgen.api;

import com.docgen.hardware.HardwareProbe;
import com.docgen.hardware.HardwareProfile;

import java.util.List;

/** A fixed machine for API tests: 32 GB of RAM and a 12 GB NVIDIA card. Reset before every test. */
final class FakeHardware implements HardwareProbe {
    static final long GIB = 1024L * 1024 * 1024;
    static final HardwareProfile DEFAULT = new HardwareProfile("Linux", "test", "x86_64", "Test CPU", 8, 16,
            32 * GIB, 24 * GIB,
            List.of(new HardwareProfile.Gpu("NVIDIA", "GeForce RTX 3060", 12 * GIB, (long) (11.2 * GIB), true,
                    "driver da placa")),
            false, new HardwareProfile.Storage("/var/lib/ollama", 500 * GIB, true));

    private volatile HardwareProfile profile = DEFAULT;

    void reset() {
        profile = DEFAULT;
    }

    void set(HardwareProfile value) {
        profile = value;
    }

    @Override
    public HardwareProfile probe() {
        return profile;
    }
}
