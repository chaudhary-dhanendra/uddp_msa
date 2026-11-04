package gov.uddp.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"gov.uddp.core", "gov.uddp.shaping"})
public class UddpCommonCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(UddpCommonCoreApplication.class, args);
    }
}
