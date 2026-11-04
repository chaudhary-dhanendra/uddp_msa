package gov.uddp.ingestion.connector;

import java.util.List;

/** Removes credentials from messages before they are logged or returned. */
final class Redact {

    private Redact() {
    }

    static String secrets(String message, List<String> secrets) {
        String out = message == null ? "" : message;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                out = out.replace(secret, "********");
            }
        }
        return out;
    }
}
