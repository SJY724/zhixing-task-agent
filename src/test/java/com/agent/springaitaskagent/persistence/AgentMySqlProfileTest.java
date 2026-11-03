package com.agent.springaitaskagent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Loads real configuration with the exact profile order used by the production main method. */
class AgentMySqlProfileTest {
    @Test void explicitlySelectedMySqlOverridesAdditionalAgentProfileWithoutConnectingToDatabase() {
        SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        application.setAdditionalProfiles("agent");
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("TASK_AGENT_DB_PASSWORD", "profile-test-not-a-secret"));
        try (var context = application.run("--spring.profiles.active=agent-mysql", "--spring.main.banner-mode=off")) {
            var environment = context.getEnvironment();
            assertEquals("com.mysql.cj.jdbc.Driver", environment.getProperty("spring.datasource.driver-class-name"));
            assertTrue(environment.getRequiredProperty("spring.datasource.url").startsWith("jdbc:mysql:"));
            assertEquals(System.getenv().getOrDefault("TASK_AGENT_DB_USERNAME", "zhixing_app"),
                    environment.getProperty("spring.datasource.username"));
            assertTrue(environment.getRequiredProperty("spring.datasource.password").length() > 0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration { }
}
