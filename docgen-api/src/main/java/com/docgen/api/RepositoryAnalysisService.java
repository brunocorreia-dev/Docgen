package com.docgen.api;

import com.docgen.DocumentationRequest;
import com.docgen.DocumentationResult;
import com.docgen.DocumentationService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Service
final class RepositoryAnalysisService {
    private final AuthorizedRepositoryRegistry registry;
    private final DocumentationService documentationService;

    RepositoryAnalysisService(
            AuthorizedRepositoryRegistry registry,
            DocumentationService documentationService
    ) {
        this.registry = registry;
        this.documentationService = documentationService;
    }

    ApiDtos.RepositoryAnalysisResponse analyze(String repositoryId, List<String> includeExtensions) {
        Path repository = registry.find(repositoryId)
                .orElseThrow(RepositoryNotFoundException::new);
        DocumentationRequest request = new DocumentationRequest(
                repository,
                null,
                DocumentationRequest.Operation.ANALYZE,
                null,
                includeExtensions,
                false,
                false
        );
        try {
            DocumentationResult result = documentationService.execute(request);
            DocumentationResult.RepositoryAnalysis analysis = result.analysis();
            int truncated = (int) analysis.includedFiles().stream()
                    .filter(DocumentationResult.SelectedFile::truncated)
                    .count();
            return new ApiDtos.RepositoryAnalysisResponse(
                    repositoryId,
                    analysis.includedFiles().size(),
                    analysis.totalCharacters(),
                    truncated,
                    analysis.skippedFiles().size(),
                    analysis.excludedFiles().size()
            );
        } catch (IOException failure) {
            if (isLimitFailure(failure)) {
                throw new AnalysisLimitExceededException();
            }
            throw new AnalysisFailedException();
        } catch (Exception failure) {
            throw new AnalysisFailedException();
        }
    }

    private static boolean isLimitFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("exceeds the limit")
                    || normalized.contains("safety limit")
                    || normalized.contains("exceeded the byte safety limit")) {
                return true;
            }
        }
        return false;
    }
}

final class RepositoryNotFoundException extends RuntimeException {
}

final class AnalysisLimitExceededException extends RuntimeException {
}

final class AnalysisFailedException extends RuntimeException {
}
