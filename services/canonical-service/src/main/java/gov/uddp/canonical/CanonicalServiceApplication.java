package gov.uddp.canonical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** State Pack driven shaping, mapping, conversion and validation; publishes canonical events and the DLQ. */
@SpringBootApplication
public class CanonicalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CanonicalServiceApplication.class, args);
    }
}
