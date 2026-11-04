package gov.uddp.core.statepack;

import java.util.List;

/**
 * The manifest ({@code state.yaml}) of a Source Pack (historically "State Pack"): one source system of one department.
 * {@code stateCode} is kept as the pack's source code for existing data; {@code departmentCode} is optional, and a
 * pack without one is assigned through the {@code department_source_pack} table.
 */
public record StatePackManifest(String packId, String stateCode, String version, String commonModelVersion,
                                String status, List<String> sources, List<String> mappings,
                                List<String> crosswalks, List<String> validation, List<String> matching,
                                List<String> survivorship, List<String> extensions, String departmentCode) {

    /** A manifest that declares no department. */
    public StatePackManifest(String packId, String stateCode, String version, String commonModelVersion,
                             String status, List<String> sources, List<String> mappings,
                             List<String> crosswalks, List<String> validation, List<String> matching,
                             List<String> survivorship, List<String> extensions) {
        this(packId, stateCode, version, commonModelVersion, status, sources, mappings, crosswalks, validation,
                matching, survivorship, extensions, null);
    }
}
