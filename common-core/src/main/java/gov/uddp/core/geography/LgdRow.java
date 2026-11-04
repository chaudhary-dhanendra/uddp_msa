package gov.uddp.core.geography;

import java.time.LocalDate;

/**
 * One entity of an LGD snapshot as the source delivers it. {@code status} is ACTIVE or INACTIVE (blank means ACTIVE);
 * {@code effectiveFrom} is optional and defaults to the snapshot's as-of date. Fields are kept as delivered so the
 * whole snapshot can be validated before anything is written to the master.
 */
public record LgdRow(int lineNo, String entityType, String code, String name, String status, String parentType,
                     String parentCode, LocalDate effectiveFrom) {
}
