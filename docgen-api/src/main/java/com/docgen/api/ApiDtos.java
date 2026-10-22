package com.docgen.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

final class ApiDtos {
    private ApiDtos() {
    }

    record HealthResponse(String status) {
    }

    record ProviderResponse(List<ProviderItem> providers) {
        ProviderResponse {
            providers = List.copyOf(providers);
        }
    }

    /**
     * Static provider metadata. {@code enabled} reflects the operator's remote
     * gate; {@code configured} only says whether a server-side key exists. The
     * key itself never leaves the server.
     */
    record ProviderItem(String id, boolean remote, String defaultModel, boolean enabled, boolean configured,
                        boolean requiresConsent) {
    }

    record RepositoryAnalysisRequest(
            @Size(max = 32)
            List<@Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9+_-]{0,15}") String> includeExtensions
    ) {
        RepositoryAnalysisRequest {
            includeExtensions = includeExtensions == null ? List.of() : List.copyOf(includeExtensions);
        }
    }

    record RepositoryAnalysisResponse(
            String repositoryId,
            int includedFileCount,
            int totalCharacters,
            int truncatedFileCount,
            int skippedFileCount,
            int excludedFileCount
    ) {
    }

    record ErrorResponse(int status, String code, String message) {
    }
}
