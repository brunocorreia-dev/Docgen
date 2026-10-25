package com.docgen.api;

import com.docgen.InstalledModel;
import com.docgen.OllamaApi;
import com.docgen.RemoteModelRefusedException;
import com.docgen.models.ModelCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The app's local preferences: the default model (only an installed catalog
 * model that runs on this computer, ADR 0003) and whether the first-run guide
 * was completed.
 */
@Service
class SettingsService {
    private final AppSettingsRepository settings;
    private final ModelCatalog catalog;
    private final OllamaApi ollama;
    private final Clock clock;

    SettingsService(AppSettingsRepository settings, ModelCatalog catalog, OllamaApi ollama, Clock clock) {
        this.settings = settings;
        this.catalog = catalog;
        this.ollama = ollama;
        this.clock = clock;
    }

    ModelDtos.SettingsView view(String owner) {
        return new ModelDtos.SettingsView(defaultModel(owner).orElse(null),
                settings.get(owner, AppSettingsRepository.ONBOARDING_COMPLETED).map(Boolean::parseBoolean).orElse(false));
    }

    /** The default model, if it is still a catalog model (the catalog can change between versions). */
    Optional<String> defaultModel(String owner) {
        return settings.get(owner, AppSettingsRepository.DEFAULT_MODEL).filter(catalog::contains);
    }

    ModelDtos.SettingsView setDefaultModel(String owner, String model) {
        if (model == null) {
            settings.remove(owner, AppSettingsRepository.DEFAULT_MODEL);
            return view(owner);
        }
        if (!catalog.contains(model)) {
            throw OllamaFailures.notInCatalog();
        }
        List<InstalledModel> installed;
        try {
            installed = ollama.installedModels();
        } catch (Exception failure) {
            throw OllamaFailures.translate(failure);
        }
        Optional<InstalledModel> match = installed.stream().filter(candidate -> candidate.name().equals(model)).findFirst();
        if (match.isEmpty()) {
            throw new StatusException(HttpStatus.NOT_FOUND, "model_not_installed", "The model is not installed in Ollama.");
        }
        if (match.get().remote()) {
            throw OllamaFailures.translate(new RemoteModelRefusedException(model));
        }
        settings.put(owner, AppSettingsRepository.DEFAULT_MODEL, model, clock.instant());
        return view(owner);
    }

    ModelDtos.SettingsView setOnboardingCompleted(String owner, boolean completed) {
        settings.put(owner, AppSettingsRepository.ONBOARDING_COMPLETED, Boolean.toString(completed), clock.instant());
        return view(owner);
    }

    /** A removed model stops being the default. */
    void forgetDefault(String owner, String model) {
        if (settings.get(owner, AppSettingsRepository.DEFAULT_MODEL).filter(model::equals).isPresent()) {
            settings.remove(owner, AppSettingsRepository.DEFAULT_MODEL);
        }
    }
}
