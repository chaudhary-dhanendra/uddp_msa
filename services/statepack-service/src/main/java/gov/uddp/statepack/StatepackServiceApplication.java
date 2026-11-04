package gov.uddp.statepack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Publishes, approves (maker-checker) and serves State Packs by version and digest. */
@SpringBootApplication
public class StatepackServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatepackServiceApplication.class, args);
    }
}
