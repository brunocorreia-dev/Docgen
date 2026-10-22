package com.docgen.api;

import com.docgen.LLMProviderFactory;
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
    private final boolean remoteEnabled;

    ApiController(RepositoryAnalysisService analysisService, DocGenApiProperties properties) {
        this.analysisService = analysisService;
        this.remoteEnabled = properties.remote().enabled();
    }

    @GetMapping("/health")
    public ApiDtos.HealthResponse health() {
        return new ApiDtos.HealthResponse("available");
    }

    @GetMapping("/providers")
    public ApiDtos.ProviderResponse providers() {
        List<ApiDtos.ProviderItem> providers = LLMProviderFactory.supportedProviders().stream()
                .map(provider -> new ApiDtos.ProviderItem(provider.id(), provider.remote(), provider.defaultModel(),
                        !provider.remote() || remoteEnabled,
                        !provider.remote() || ProviderConfig.groqKeyConfigured(),
                        provider.remote()))
                .toList();
        return new ApiDtos.ProviderResponse(providers);
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
