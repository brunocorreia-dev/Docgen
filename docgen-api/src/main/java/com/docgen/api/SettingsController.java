package com.docgen.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local preferences: default model and the first-run guide. */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {
    private final SettingsService settings;

    SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    ModelDtos.SettingsView view() {
        return settings.view(CurrentUser.username());
    }

    @PutMapping("/default-model")
    ModelDtos.SettingsView defaultModel(@Valid @RequestBody ModelDtos.DefaultModelRequest request) {
        return settings.setDefaultModel(CurrentUser.username(), request.model());
    }

    @PutMapping("/onboarding")
    ModelDtos.SettingsView onboarding(@Valid @RequestBody ModelDtos.OnboardingRequest request) {
        return settings.setOnboardingCompleted(CurrentUser.username(), request.completed());
    }
}
