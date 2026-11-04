package gov.uddp.core.connector;

import gov.uddp.core.setup.LocalSettingsStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link ConnectorSecrets} backed by the existing AES-GCM settings store. */
@Component
public class StoreConnectorSecrets implements ConnectorSecrets {
    private final LocalSettingsStore store;

    public StoreConnectorSecrets(LocalSettingsStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> get(String name) {
        return store.findSecret(name);
    }

    @Override
    public void put(String name, String value) {
        store.saveSecret(name, value);
    }
}
