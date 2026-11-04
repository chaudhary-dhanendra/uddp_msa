package gov.uddp.stewardship;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Edge for the onboarding UI: read APIs, review queue, onboarding orchestration. */
@SpringBootApplication
public class ApiStewardshipServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiStewardshipServiceApplication.class, args);
    }
}
