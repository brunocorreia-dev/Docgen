package com.docgen.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final RepositoryAnalysisService analysisService;

    ApiController(RepositoryAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/health")
    public ApiDtos.HealthResponse health() {
        return new ApiDtos.HealthResponse("available");
    }

    @PostMapping("/repositories/{repositoryId}/analysis")
    public ApiDtos.RepositoryAnalysisResponse analyze(
            @PathVariable("repositoryId")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
            String repositoryId,
            @Valid @RequestBody(required = false) ApiDtos.RepositoryAnalysisRequest request
    ) {
        List<String> includeExtensions = request == null ? List.of() : request.includeExtensions();
        return analysisService.analyze(repositoryId, includeExtensions);
    }
}
