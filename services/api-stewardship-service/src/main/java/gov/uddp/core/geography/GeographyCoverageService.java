package gov.uddp.core.geography;

import gov.uddp.core.read.Db;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Geography coverage per source (Track F5): for each geography level the current canonical Residents of a source
 * carry, how many resolved to an LGD code (given, crosswalked, named or derived from a lower level) and how many stayed
 * UNKNOWN. Percentages are of the source's current Residents, so a level a source never supplies shows no row rather
 * than a fabricated 0% resolved.
 */
@Service
public class GeographyCoverageService {
    /** One level of one source; {@code records} is every current canonical Resident of the source. */
    public record LevelCoverage(String stateCode, String sourceSystem, String level, long records, long supplied,
                                long resolved, long unknown, double resolvedPercent, double unknownPercent) {
    }

    private static final String SQL = "with source as (select state_code,source_system,count(*) as records"
            + " from canonical_resident_current where (cast(? as text) is null or state_code=?)"
            + " and (cast(? as text) is null or source_system=?) group by state_code,source_system),"
            + " levels as (select c.state_code,c.source_system,e.key as level,"
            + " count(*) as supplied,"
            + " count(*) filter (where nullif(e.value->>'lgd','') is not null"
            + " and coalesce(e.value->>'status','') <> 'UNKNOWN') as resolved,"
            + " count(*) filter (where e.value->>'status'='UNKNOWN') as unknown"
            + " from canonical_resident_current c cross join lateral jsonb_each(coalesce("
            + " c.canonical_payload->'extensions'->'uddp.geography','{}'::jsonb)) e(key,value)"
            + " where e.key <> 'pincode' and jsonb_typeof(e.value)='object'"
            + " and (cast(? as text) is null or c.state_code=?) and (cast(? as text) is null or c.source_system=?)"
            + " group by c.state_code,c.source_system,e.key)"
            + " select l.state_code,l.source_system,l.level,s.records,l.supplied,l.resolved,l.unknown"
            + " from levels l join source s on s.state_code=l.state_code and s.source_system=l.source_system"
            + " order by l.state_code,l.source_system,l.level";

    private final Db db;

    public GeographyCoverageService(Db db) {
        this.db = db;
    }

    /** Coverage of every source, optionally limited to one state and one source system. */
    public List<LevelCoverage> coverage(String stateCode, String sourceSystem) {
        String state = blankToNull(stateCode);
        String source = blankToNull(sourceSystem);
        return db.list(SQL, row -> {
            long records = row.getLong(4);
            long resolved = row.getLong(6);
            long unknown = row.getLong(7);
            return new LevelCoverage(row.getString(1), row.getString(2), row.getString(3), records, row.getLong(5),
                    resolved, unknown, percent(resolved, records), percent(unknown, records));
        }, state, state, source, source, state, state, source, source);
    }

    public List<LevelCoverage> coverage(String stateCode) {
        return coverage(stateCode, null);
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
