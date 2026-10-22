package com.docgen.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to a fixed, sanitized error body. Exception messages are
 * never echoed because they can contain paths, provider output or user data.
 */
@RestControllerAdvice
final class ApiExceptionHandler {
    /*
     * Spring Framework 7 validates controller methods as a whole whenever a
     * parameter carries a constraint annotation (for example the @Pattern on a
     * path variable). Invalid request bodies then surface as
     * HandlerMethodValidationException instead of MethodArgumentNotValidException,
     * so both must be mapped to 400 or they fall through to the generic 500.
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class,
            InvalidRequestException.class
    })
    ResponseEntity<ApiDtos.ErrorResponse> badRequest() {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "The request is invalid.");
    }

    @ExceptionHandler(RepositoryNotFoundException.class)
    ResponseEntity<ApiDtos.ErrorResponse> notFound() {
        return error(HttpStatus.NOT_FOUND, "repository_not_found", "The repository was not found.");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiDtos.ErrorResponse> resourceNotFound() {
        return error(HttpStatus.NOT_FOUND, "not_found", "The resource was not found.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiDtos.ErrorResponse> routeNotFound() {
        return error(HttpStatus.NOT_FOUND, "not_found", "The resource was not found.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiDtos.ErrorResponse> methodNotAllowed() {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "The method is not allowed.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiDtos.ErrorResponse> unsupportedMediaType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                "The content type is not supported.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiDtos.ErrorResponse> notAcceptable() {
        return error(HttpStatus.NOT_ACCEPTABLE, "not_acceptable", "The requested representation is not available.");
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiDtos.ErrorResponse> conflict(ConflictException conflict) {
        return error(HttpStatus.CONFLICT, conflict.code(), conflict.publicMessage());
    }

    @ExceptionHandler(UnprocessableContentException.class)
    ResponseEntity<ApiDtos.ErrorResponse> unprocessable(UnprocessableContentException failure) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, failure.code(), failure.publicMessage());
    }

    @ExceptionHandler(CapacityExceededException.class)
    ResponseEntity<ApiDtos.ErrorResponse> capacityExceeded(CapacityExceededException failure) {
        return error(HttpStatus.TOO_MANY_REQUESTS, failure.code(), failure.publicMessage());
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ApiDtos.ErrorResponse> payloadTooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large", "The request exceeds the allowed size.");
    }

    @ExceptionHandler(AnalysisLimitExceededException.class)
    ResponseEntity<ApiDtos.ErrorResponse> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "analysis_limit_exceeded",
                "The repository exceeds an analysis limit.");
    }

    @ExceptionHandler({AnalysisFailedException.class, Exception.class})
    ResponseEntity<ApiDtos.ErrorResponse> internalError() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "The request could not be processed.");
    }

    static ResponseEntity<ApiDtos.ErrorResponse> error(
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status)
                .body(new ApiDtos.ErrorResponse(status.value(), code, message));
    }
}
