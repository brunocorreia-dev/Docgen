package com.docgen.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ollama status, hardware diagnosis and the model manager. Model names travel
 * as a query parameter or in the body because a tag may contain a slash.
 */
@RestController
@RequestMapping("/api/v1")
class ModelController {
    private final ModelService models;
    private final ModelPullService pulls;

    ModelController(ModelService models, ModelPullService pulls) {
        this.models = models;
        this.pulls = pulls;
    }

    @GetMapping("/ollama/status")
    ModelDtos.OllamaStatus status() {
        return models.status();
    }

    @GetMapping("/hardware")
    ModelDtos.HardwareView hardware() {
        return models.hardware();
    }

    @GetMapping("/models/catalog")
    ModelDtos.CatalogView catalog() {
        return models.catalog();
    }

    @GetMapping("/models")
    ModelDtos.InstalledList installed() {
        return models.installed(CurrentUser.username());
    }

    @GetMapping("/models/details")
    ModelDtos.DetailsView details(@RequestParam("name") @Pattern(regexp = ModelDtos.MODEL) String name) {
        return models.details(name);
    }

    @DeleteMapping("/models")
    ResponseEntity<Void> delete(@RequestParam("name") @Pattern(regexp = ModelDtos.MODEL) String name) {
        models.delete(CurrentUser.username(), name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/models/test")
    ModelDtos.TestResult test(@Valid @RequestBody ModelDtos.ModelRequest request) {
        return models.test(request.model());
    }

    @PostMapping("/models/pulls")
    ResponseEntity<ModelDtos.PullView> pull(@Valid @RequestBody ModelDtos.ModelRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pulls.start(request.model()));
    }

    @GetMapping("/models/pulls")
    ModelDtos.PullList pulls() {
        return pulls.list();
    }

    @GetMapping("/models/pulls/{pullId}")
    ModelDtos.PullView pullStatus(@PathVariable("pullId") String pullId) {
        return pulls.get(pullId);
    }

    @DeleteMapping("/models/pulls/{pullId}")
    ResponseEntity<ModelDtos.PullView> cancel(@PathVariable("pullId") String pullId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pulls.cancel(pullId));
    }
}
