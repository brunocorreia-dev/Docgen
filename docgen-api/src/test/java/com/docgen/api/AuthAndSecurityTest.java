package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthAndSecurityTest extends ApiTestSupport {
    @Test
    void issuesBearerTokensThatAuthenticateAndCanBeRevoked() throws Exception {
        String token = token();

        mvc.perform(authed(get("/api/v1/auth/session"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USER));
        mvc.perform(authed(delete("/api/v1/auth/token"), token)).andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/auth/session"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongCredentialsWithoutRevealingWhichPartWasWrong() throws Exception {
        for (String body : new String[]{
                "{\"username\":\"" + USER + "\",\"password\":\"wrong-password-xx\"}",
                "{\"username\":\"nobody\",\"password\":\"wrong-password-xx\"}"}) {
            mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("invalid_credentials"));
        }
    }

    @Test
    void throttlesRepeatedFailedSignIns() throws Exception {
        String body = "{\"username\":\"throttled-user\",\"password\":\"wrong-password-xx\"}";
        for (int attempt = 0; attempt < LoginThrottle.FREE_ATTEMPTS; attempt++) {
            mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_attempts"));
    }

    @Test
    void basicAuthenticationSharesTheLockout() throws Exception {
        String body = "{\"username\":\"basic-target\",\"password\":\"wrong-password-xx\"}";
        for (int attempt = 0; attempt < LoginThrottle.FREE_ATTEMPTS; attempt++) {
            mvc.perform(get("/api/v1/projects").with(httpBasic("basic-target", "wrong-password-xx")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void forgedOrMissingTokensAreRejectedAndTheWebClientGetsABearerChallenge() throws Exception {
        mvc.perform(authed(get("/api/v1/projects"), "forged-token-value-1234567890"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"docgen-local\""));
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"docgen-local\""));
    }

    @Test
    void basicAuthenticationStillWorksForScripts() throws Exception {
        mvc.perform(get("/api/v1/projects").with(httpBasic(USER, PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void stateChangingRequestsRequireANonSimpleContentType() throws Exception {
        String token = token();
        // A cross-site HTML form can only send these types, so they must never reach a handler.
        for (String type : new String[]{"text/plain", "application/x-www-form-urlencoded", "multipart/form-data"}) {
            mvc.perform(authed(post("/api/v1/projects"), token).contentType(type).content(projectJson("x", "ollama")))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("unsupported_media_type"));
        }
        mvc.perform(authed(post("/api/v1/projects"), token).content(projectJson("x", "ollama")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void grantsNoCrossOriginAccess() throws Exception {
        mvc.perform(options("/api/v1/projects")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/health").header("Origin", "https://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void rejectsForeignHostHeadersToBlockDnsRebinding() throws Exception {
        mvc.perform(get("/api/v1/health").header("Host", "attacker.example"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_host"));
        mvc.perform(get("/api/v1/health").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
    }

    @Test
    void servesTheWorkspaceWithAStrictContentSecurityPolicy() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"));
        mvc.perform(get("/js/main.js")).andExpect(status().isOk());
    }

    @Test
    void unknownRoutesAreDeniedWithoutLeakingDetails() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/anything").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is not authorized."));
        mvc.perform(get("/api/v1/does-not-exist").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void providersNeverExposeCredentials() throws Exception {
        String body = mvc.perform(get("/api/v1/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.id == 'groq')].requiresConsent").value(true))
                .andExpect(jsonPath("$.providers[?(@.id == 'ollama')].defaultModel").value("llama3.2"))
                .andReturn().getResponse().getContentAsString();
        String key = System.getenv("GROQ_API_KEY");
        if (key != null && !key.isBlank()) {
            org.junit.jupiter.api.Assertions.assertFalse(body.contains(key));
        }
        org.junit.jupiter.api.Assertions.assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("apikey"));
    }
}
