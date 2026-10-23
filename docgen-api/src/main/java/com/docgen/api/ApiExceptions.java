package com.docgen.api;

/**
 * Failures with fixed, client-safe codes and messages. Messages are constants
 * chosen by the server; they never embed user input, paths or provider text.
 */
final class InvalidRequestException extends RuntimeException {
}

final class ResourceNotFoundException extends RuntimeException {
}

final class PayloadTooLargeException extends RuntimeException {
}

abstract class PublicApiException extends RuntimeException {
    private final String code;
    private final String publicMessage;

    PublicApiException(String code, String publicMessage) {
        super(code);
        this.code = code;
        this.publicMessage = publicMessage;
    }

    String code() {
        return code;
    }

    String publicMessage() {
        return publicMessage;
    }
}

final class ConflictException extends PublicApiException {
    ConflictException(String code, String publicMessage) {
        super(code, publicMessage);
    }
}

final class UnprocessableContentException extends PublicApiException {
    UnprocessableContentException(String code, String publicMessage) {
        super(code, publicMessage);
    }
}

final class ForbiddenException extends PublicApiException {
    ForbiddenException(String code, String publicMessage) {
        super(code, publicMessage);
    }
}

final class CapacityExceededException extends PublicApiException {
    CapacityExceededException(String code, String publicMessage) {
        super(code, publicMessage);
    }
}
