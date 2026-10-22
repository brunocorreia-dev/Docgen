package com.docgen.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthenticationManager authenticationManager;
    private final AuthTokenService tokens;

    AuthController(AuthenticationManager authenticationManager, AuthTokenService tokens) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
    }

    record TokenRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 256) String password
    ) {
    }

    record TokenResponse(String accessToken, String tokenType, Instant expiresAt, String username) {
    }

    record SessionResponse(String username) {
    }

    @PostMapping("/token")
    ResponseEntity<?> issue(@Valid @RequestBody TokenRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (ThrottledAuthenticationManager.ThrottledException locked) {
            return ApiExceptionHandler.error(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "Too many failed sign-in attempts. Try again later.");
        } catch (AuthenticationException failure) {
            return ApiExceptionHandler.error(HttpStatus.UNAUTHORIZED, "invalid_credentials",
                    "The username or password is incorrect.");
        }
        AuthTokenService.IssuedToken issued = tokens.issue(authentication);
        return ResponseEntity.ok(new TokenResponse(issued.token(), "Bearer", issued.expiresAt(),
                authentication.getName()));
    }

    @GetMapping("/session")
    SessionResponse session() {
        return new SessionResponse(CurrentUser.username());
    }

    @DeleteMapping("/token")
    ResponseEntity<Void> revoke(HttpServletRequest request) {
        tokens.revoke(BearerTokenAuthenticationFilter.extract(request));
        return ResponseEntity.noContent().build();
    }
}
