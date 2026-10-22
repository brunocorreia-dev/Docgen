package com.docgen.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(DocGenApiProperties.class)
public class DocGenApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocGenApiApplication.class, args);
    }
}
