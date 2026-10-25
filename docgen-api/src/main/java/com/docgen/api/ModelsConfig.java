package com.docgen.api;

import com.docgen.OllamaApi;
import com.docgen.OllamaClient;
import com.docgen.hardware.HardwareProbe;
import com.docgen.hardware.SystemHardwareProbe;
import com.docgen.models.ModelCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The local Ollama client, the hardware probe and the model catalog. Tests
 * replace the first two with fakes so they never reach a real Ollama or
 * depend on the machine they run on.
 */
@Configuration(proxyBeanMethods = false)
class ModelsConfig {
    @Bean
    OllamaApi ollamaApi(DocGenApiProperties properties) {
        return new OllamaClient(properties.ollama().endpoint());
    }

    @Bean
    HardwareProbe hardwareProbe() {
        return new SystemHardwareProbe();
    }

    @Bean
    ModelCatalog modelCatalog() {
        return ModelCatalog.load();
    }
}
