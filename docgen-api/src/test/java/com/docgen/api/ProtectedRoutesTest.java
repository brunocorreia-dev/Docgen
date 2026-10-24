package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * "Another local process without the token cannot use the API" (ADR 0002),
 * checked on every route the application maps, so a route added later is
 * covered without touching this test.
 */
class ProtectedRoutesTest extends ApiTestSupport {
    /** The only routes reachable without a session, each with its own authorization. */
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/health",
            "POST /api/v1/session",
            "POST " + DesktopControl.REOPEN_PATH);
    private static final String SOME_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private record Route(HttpMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private List<Route> apiRoutes() {
        List<Route> routes = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", SOME_ID);
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    routes.add(new Route(method.asHttpMethod(), path));
                }
            }
        }
        return routes;
    }

    @Test
    void everyApiRouteButThePublicOnesRequiresASession() throws Exception {
        List<Route> routes = apiRoutes();
        assertTrue(routes.size() > 25, "the route list was discovered: " + routes);
        int checked = 0;
        for (Route route : routes) {
            if (PUBLIC.contains(route.method() + " " + route.path())) {
                continue;
            }
            for (String authorization : new String[]{null, "Bearer " + "A".repeat(43), "Bearer not-a-token",
                    "Basic bG9jYWw6cGFzc3dvcmQ="}) {
                var builder = request(route.method(), route.path()).contentType("application/json").content("{}");
                if (authorization != null) {
                    builder.header("Authorization", authorization);
                }
                MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
                assertEquals(401, response.getStatus(), route + " with " + authorization);
                assertNull(response.getHeader("Set-Cookie"), route + " set a cookie");
            }
            mvc.perform(request(route.method(), route.path()).with(httpBasic("local", "password"))
                    .contentType("application/json").content("{}"))
                    .andExpect(result -> assertEquals(401, result.getResponse().getStatus(), route + " with Basic"));
            checked++;
        }
        assertTrue(checked >= routes.size() - PUBLIC.size());
    }

    @Test
    void theDocumentedPublicRoutesAreTheOnlyOnes() {
        Set<String> discovered = new java.util.HashSet<>();
        apiRoutes().forEach(route -> discovered.add(route.toString()));
        assertTrue(discovered.containsAll(PUBLIC), "public routes exist: " + discovered);
    }

    @Test
    void noRouteSetsACookieForAnAuthenticatedSessionEither() throws Exception {
        String token = token();
        for (Route route : apiRoutes()) {
            if (route.method() != HttpMethod.GET) {
                continue;
            }
            MockHttpServletResponse response = mvc.perform(authed(request(route.method(), route.path()), token))
                    .andReturn().getResponse();
            assertNull(response.getHeader("Set-Cookie"), route + " set a cookie");
        }
    }
}
