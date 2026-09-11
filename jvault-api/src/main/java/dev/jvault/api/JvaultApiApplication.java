package dev.jvault.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The HTTP entry point.
 *
 * <p>Deliberately thin. Every endpoint is an adapter over the same application core the Kafka
 * consumer uses, so that "consistent behaviour across all entry points" is structural rather than
 * a thing to be re-checked whenever either side changes (docs/06-rest-api.md 6.3).
 */
@SpringBootApplication
public class JvaultApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(JvaultApiApplication.class, args);
    }
}
