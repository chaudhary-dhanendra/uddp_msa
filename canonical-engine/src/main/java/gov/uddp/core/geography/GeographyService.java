package gov.uddp.core.geography;

import gov.uddp.core.read.Db;
import gov.uddp.core.read.Page;
import gov.uddp.core.read.PageQuery;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reads the LGD geography master (Track F2): server-side search by type, code or name within a parent area, ancestors
 * and descendants, code validation (with optional parent consistency) and as-of lookups. Everything reads the
 * versioned tables written by {@link LgdImportService}; nothing here knows any particular state.
 */
@Service
public class GeographyService {
    /** How a code compares with the master. */
    public enum CodeStatus { ACTIVE_VALID, KNOWN_INACTIVE, UNKNOWN }

    /** One entity as currently known (or as of a date): its name, status and immediate parent. */
    public record GeoEntity(String entityType, String code, String name, String status, String parentType,
                            String parentCode, String parentName) {
    }

    /**
     * The result of validating a code. {@code parentConsistent} is null when no parent was given (or the code is
     * unknown), else whether the given parent is the entity or one of its ancestors.
     */
    public record CodeValidation(String entityType, String code, CodeStatus status, GeoEntity entity,
                                 Boolean parentConsistent) {
    }

    /** One entity type with its place in the hierarchy. */
    public record EntityType(String entityType, int levelOrder, String label) {
    }

    private static final Map<String, String> SORTABLE = Map.of("name", "v.name", "code", "v.code",
            "entityType", "t.level_order");
    private static final String CURRENT = "select v.entity_type,v.code,v.name,v.status,r.parent_type,r.parent_code,"
            + "pv.name as parent_name from lgd_entity_version v"
            + " join lgd_entity_type t on t.entity_type=v.entity_type"
            + " left join lgd_relationship r on r.child_type=v.entity_type and r.child_code=v.code and r.valid_to is null"
            + " left join lgd_entity_version pv on pv.entity_type=r.parent_type and pv.code=r.parent_code"
            + " and pv.effective_to is null where v.effective_to is null";
    private static final String SCOPE = "with recursive scope(entity_type,code) as (select cast(? as text),"
            + "cast(? as text) union select r.child_type,r.child_code from lgd_relationship r join scope s"
            + " on r.parent_type=s.entity_type and r.parent_code=s.code where r.valid_to is null) ";

    private final Db db;

    public GeographyService(Db db) {
        this.db = db;
    }

    public List<EntityType> entityTypes() {
        return db.list("select entity_type,level_order,label from lgd_entity_type order by level_order,entity_type",
                r -> new EntityType(r.getString(1), r.getInt(2), r.getString(3)));
    }

    /** True once any snapshot has been activated. A loaded master stays loaded, so a true answer is kept. */
    public boolean loaded() {
        if (loadedSeen) {
            return true;
        }
        boolean loaded = db.count("select count(*) from lgd_master_state") > 0;
        loadedSeen = loaded;
        return loaded;
    }

    private volatile boolean loadedSeen;

    /**
     * Searches current entities. {@code text} matches a code exactly or a name by case-insensitive substring;
     * {@code parentType}/{@code parentCode} limit the search to that area (any depth); {@code status} is ACTIVE,
     * INACTIVE or null for both.
     */
    public Page<GeoEntity> search(String entityType, String text, String parentType, String parentCode, String status,
                                  Integer page, Integer size) {
        PageQuery query = PageQuery.of(page, size, null, SORTABLE, "name,asc", "v.entity_type, v.code");
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        boolean scoped = parentType != null && !parentType.isBlank() && parentCode != null && !parentCode.isBlank();
        if (scoped) {
            sql.append(SCOPE);
            parameters.add(upper(parentType));
            parameters.add(parentCode.strip());
        }
        sql.append(CURRENT);
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" and v.entity_type=?");
            parameters.add(upper(entityType));
        }
        if (text != null && !text.isBlank()) {
            sql.append(" and (v.code=? or lower(v.name) like ?)");
            parameters.add(text.strip());
            parameters.add("%" + text.strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "\\_") + "%");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and v.status=?");
            parameters.add(upper(status));
        }
        if (scoped) {
            sql.append(" and (v.entity_type,v.code) in (select entity_type,code from scope)"
                    + " and not (v.entity_type=? and v.code=?)");
            parameters.add(upper(parentType));
            parameters.add(parentCode.strip());
        }
        Object[] bound = parameters.toArray();
        long total = db.count("select count(*) from (" + sql + ") x", bound);
        List<GeoEntity> items = db.list(sql + query.sql(), GeographyService::entity, bound);
        return Page.of(items, query, total);
    }

    /**
     * The current entity for a type and code. Lookups are cached for {@link #FIND_TTL_MILLIS}: mapping resolves the
     * geography of every row (and its ancestors), so a large delivery or dry run would otherwise query the master
     * several times per row. The master changes rarely; a change is visible to mapping within the TTL. Only codes
     * that were found are cached: a code missing now (the master not projected here yet, or loaded since) is looked
     * up again next time.
     */
    public Optional<GeoEntity> find(String entityType, String code) {
        if (entityType == null || code == null) {
            return Optional.empty();
        }
        String key = upper(entityType) + "|" + code.strip();
        long now = System.currentTimeMillis();
        CachedFind cached = findCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.entity();
        }
        Optional<GeoEntity> entity = db.one(CURRENT + " and v.entity_type=? and v.code=?", GeographyService::entity,
                upper(entityType), code.strip());
        if (findCache.size() > FIND_CACHE_MAX) {
            findCache.clear();
        }
        if (entity.isPresent()) {
            findCache.put(key, new CachedFind(entity, now + FIND_TTL_MILLIS));
        }
        return entity;
    }

    /** Forgets cached lookups (after the master is loaded or changed in this process). */
    public void clearCache() {
        findCache.clear();
    }

    static final long FIND_TTL_MILLIS = 60_000;
    private static final int FIND_CACHE_MAX = 200_000;

    private record CachedFind(Optional<GeoEntity> entity, long expiresAt) {
    }

    private final java.util.Map<String, CachedFind> findCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** The entity's ancestors, top-down (the state first, the immediate parent last). */
    public List<GeoEntity> ancestors(String entityType, String code) {
        List<GeoEntity> chain = new ArrayList<>();
        Optional<GeoEntity> current = find(entityType, code);
        int guard = 0;
        while (current.isPresent() && current.get().parentType() != null && guard++ < 20) {
            current = find(current.get().parentType(), current.get().parentCode());
            current.ifPresent(chain::add);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** Entities below this one at any depth, optionally of one type. */
    public Page<GeoEntity> descendants(String entityType, String code, String childType, Integer page, Integer size) {
        return search(childType, null, entityType, code, null, page, size);
    }

    /** ACTIVE_VALID, KNOWN_INACTIVE or UNKNOWN, and whether the given parent is consistent with the code. */
    public CodeValidation validate(String entityType, String code, String parentType, String parentCode) {
        Optional<GeoEntity> found = find(entityType, code);
        if (found.isEmpty()) {
            return new CodeValidation(upper(entityType), code, CodeStatus.UNKNOWN, null, null);
        }
        GeoEntity entity = found.get();
        CodeStatus status = LgdImportService.ACTIVE.equals(entity.status()) ? CodeStatus.ACTIVE_VALID
                : CodeStatus.KNOWN_INACTIVE;
        Boolean consistent = null;
        if (parentType != null && !parentType.isBlank() && parentCode != null && !parentCode.isBlank()) {
            String type = upper(parentType);
            String parent = parentCode.strip();
            consistent = ancestors(entity.entityType(), entity.code()).stream()
                    .anyMatch(a -> a.entityType().equals(type) && a.code().equals(parent));
        }
        return new CodeValidation(entity.entityType(), entity.code(), status, entity, consistent);
    }

    /** The entity as it was on {@code date}: the name, status and parent in force that day. */
    public Optional<GeoEntity> asOf(String entityType, String code, LocalDate date) {
        Date day = Date.valueOf(date);
        return db.one("select v.entity_type,v.code,v.name,v.status,r.parent_type,r.parent_code,pv.name"
                + " from lgd_entity_version v"
                + " left join lgd_relationship r on r.child_type=v.entity_type and r.child_code=v.code"
                + " and r.valid_from<=? and (r.valid_to is null or r.valid_to>?)"
                + " left join lgd_entity_version pv on pv.entity_type=r.parent_type and pv.code=r.parent_code"
                + " and pv.effective_from<=? and (pv.effective_to is null or pv.effective_to>?)"
                + " where v.entity_type=? and v.code=? and v.effective_from<=?"
                + " and (v.effective_to is null or v.effective_to>?)",
                GeographyService::entity, day, day, day, day, upper(entityType), code.strip(), day, day);
    }

    /** Code, name and status of every current entity of one type, for matching many values in memory. */
    public List<String[]> names(String entityType) {
        return db.list("select code, name, status from lgd_entity_version where effective_to is null and entity_type=?"
                + " order by name, code", r -> new String[] {r.getString(1), r.getString(2), r.getString(3)},
                upper(entityType));
    }

    /** Codes of current entities of one type with this status (null for every status). */
    public List<String> codes(String entityType, String status) {
        return db.list("select code from lgd_entity_version where effective_to is null and entity_type=?"
                + " and (cast(? as text) is null or status=?) order by code", r -> r.getString(1), upper(entityType),
                status, status);
    }

    private static GeoEntity entity(java.sql.ResultSet r) throws java.sql.SQLException {
        return new GeoEntity(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7));
    }

    static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
