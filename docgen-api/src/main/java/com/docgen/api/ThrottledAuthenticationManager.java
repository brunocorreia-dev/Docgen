package com.docgen.api;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Applies {@link LoginThrottle} to every password check, so HTTP Basic and
 * the token endpoint share the same failure counter and lockout.
 */
final class ThrottledAuthenticationManager implements AuthenticationManager {
    private final AuthenticationManager delegate;
    private final LoginThrottle throttle;

    ThrottledAuthenticationManager(AuthenticationManager delegate, LoginThrottle throttle) {
        this.delegate = delegate;
        this.throttle = throttle;
    }

    /** Raised while a username is locked out; Basic clients see a plain 401. */
    static final class ThrottledException extends AuthenticationException {
        ThrottledException() {
            super("Too many failed sign-in attempts");
        }
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();
        if (throttle.isLocked(username)) {
            throw new ThrottledException();
        }
        try {
            Authentication result = delegate.authenticate(authentication);
            throttle.recordSuccess(username);
            return result;
        } catch (AuthenticationException failure) {
            throttle.recordFailure(username);
            throw failure;
        }
    }
}
