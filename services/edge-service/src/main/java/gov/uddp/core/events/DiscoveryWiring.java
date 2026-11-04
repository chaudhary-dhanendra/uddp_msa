package gov.uddp.core.events;

import gov.uddp.contracts.ServiceUrls;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Configuration;

/** Resolves lb://service-name addresses through this service's discovery client (Eureka), at call time. */
@Configuration(proxyBeanMethods = false)
public class DiscoveryWiring {

    public DiscoveryWiring(org.springframework.beans.factory.ObjectProvider<DiscoveryClient> discovery) {
        DiscoveryClient client = discovery.getIfAvailable();
        if (client != null) {
            ServiceUrls.useRegistry(name -> client.getInstances(name).stream().map(ServiceInstance::getUri).toList());
        }
    }
}
