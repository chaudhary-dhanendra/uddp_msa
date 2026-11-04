package gov.uddp.shaping.api;

/** Resolves layout operations and field parsers by {@code name@version}; throws IllegalArgumentException if unknown. */
public interface OperationLookup {

    LayoutOperation operation(String nameAtVersion);

    FieldParser parser(String nameAtVersion);
}
