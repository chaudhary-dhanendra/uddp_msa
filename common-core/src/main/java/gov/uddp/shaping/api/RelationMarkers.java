package gov.uddp.shaping.api;

/** Detects the relation markers used in composite name-and-parentage values. */
public interface RelationMarkers {
    boolean hasRelationMarker(String value);
}
