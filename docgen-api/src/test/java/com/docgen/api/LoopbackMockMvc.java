package com.docgen.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MockMvc requests carry no Host header by default. The server accepts only
 * {@code 127.0.0.1:<port>} and {@code localhost:<port>} (DNS-rebinding defense),
 * so every test request addresses the port of the test profile, as the browser
 * would address the real one.
 */
@TestConfiguration
class LoopbackMockMvc {
    static final int PORT = 18080;
    static final String HOST = "127.0.0.1:" + PORT;
    static final String ORIGIN = "http://" + HOST;

    @Bean
    MockMvcBuilderCustomizer loopbackHost() {
        return builder -> builder.defaultRequest(get("/").header("Host", HOST));
    }
}
