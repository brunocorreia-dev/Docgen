package com.docgen.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {
    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @PostMapping
    ResponseEntity<ProjectDtos.ProjectView> create(@Valid @RequestBody ProjectDtos.ProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.create(CurrentUser.username(), request));
    }

    @GetMapping
    ProjectDtos.ProjectList list() {
        return projects.list(CurrentUser.username());
    }

    @GetMapping("/{projectId}")
    ProjectDtos.ProjectView get(@PathVariable("projectId") String projectId) {
        return projects.get(CurrentUser.username(), projectId);
    }

    @PutMapping("/{projectId}")
    ProjectDtos.ProjectView update(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody ProjectDtos.ProjectRequest request
    ) {
        return projects.update(CurrentUser.username(), projectId, request);
    }

    @DeleteMapping("/{projectId}")
    ResponseEntity<Void> delete(@PathVariable("projectId") String projectId) {
        projects.delete(CurrentUser.username(), projectId);
        return ResponseEntity.noContent().build();
    }
}
