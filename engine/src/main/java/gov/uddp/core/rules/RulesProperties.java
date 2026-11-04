package gov.uddp.core.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configures the neutral owner whose approved SURVIVORSHIP DMN rules govern Golden fields. */
@ConfigurationProperties(prefix = "uddp.rules")
public class RulesProperties {
    private RuleSet.OwnerType survivorshipOwnerType = RuleSet.OwnerType.DEPARTMENT;
    private String survivorshipOwnerId;

    public RuleSet.OwnerType getSurvivorshipOwnerType() { return survivorshipOwnerType; }
    public void setSurvivorshipOwnerType(RuleSet.OwnerType survivorshipOwnerType) { this.survivorshipOwnerType = survivorshipOwnerType; }
    public String getSurvivorshipOwnerId() { return survivorshipOwnerId; }
    public void setSurvivorshipOwnerId(String survivorshipOwnerId) { this.survivorshipOwnerId = survivorshipOwnerId; }
}
