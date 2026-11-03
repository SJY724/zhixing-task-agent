package com.agent.springaitaskagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Knowledge task application; the agent profile provides its workflow and persistence configuration. */
@SpringBootApplication
public class SpringAiTaskAgentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SpringAiTaskAgentApplication.class);
        application.setAdditionalProfiles("agent");
        application.run(args);
    }
}
