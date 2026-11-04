package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A Golden Resident's assets across states. The Golden link is resolved from the current Golden membership of the
 * holder's source record, and the benefit link from the asset's source benefit ID within the same state, on every
 * read. Read-only.
 */
@Service
public class AssetReadService {
    /** An asset's present statement and the benefit it relates to (null when none or not found). */
    public record AssetView(UUID assetId, String assetRecordKey, String stateCode, String sourceSystem,
                            String sourceAssetId, String assetType, String description, BigDecimal quantity,
                            String unit, BigDecimal value, String holderSourceRecordKey,
                            String linkedSourceBenefitId, UUID linkedBenefitId, String linkedSchemeId,
                            JsonNode attributes, OffsetDateTime createdAt, OffsetDateTime observedAt) {
    }

    /** A Golden Resident's assets. */
    public record ResidentAssets(UUID goldenResidentId, String status, int assetCount, List<AssetView> assets) {
    }

    private final Db db;
    private final GoldenVisibility goldens;

    public AssetReadService(Db db, GoldenVisibility goldens) {
        this.db = db;
        this.goldens = goldens;
    }

    /** A Golden Resident's assets across states; 404 for an unknown Golden ID. */
    public ResidentAssets assetsOf(UUID goldenResidentId) {
        String status = db.one("select g.status from golden_resident g where g.golden_resident_id = ?",
                r -> r.getString("status"), goldenResidentId)
                .orElseThrow(() -> new ReadNotFoundException("Golden Resident does not exist: " + goldenResidentId));
        goldens.requireVisible(goldenResidentId);
        var assets = db.list("select s.asset_id, s.asset_record_key, s.state_code, s.source_system, s.source_asset_id,"
                        + " s.asset_type, s.description, s.quantity, s.unit, s.value, s.holder_source_record_key,"
                        + " s.linked_source_benefit_id, s.attributes::text as attributes_text, s.created_at,"
                        + " s.observed_at, b.benefit_id as linked_benefit_id, b.scheme_id as linked_scheme_id"
                        + " from asset_state s left join benefit b on b.state_code = s.state_code"
                        + " and b.source_benefit_id = s.linked_source_benefit_id"
                        + " where exists (select 1 from golden_resident_membership m"
                        + " where m.golden_resident_id = ? and m.effective_to is null"
                        + " and m.source_record_key = s.holder_source_record_key)"
                        + Db.inScope("s.state_code") + " order by s.asset_type, s.asset_id",
                r -> new AssetView(Db.uuid(r, "asset_id"), r.getString("asset_record_key"), r.getString("state_code"),
                        r.getString("source_system"), r.getString("source_asset_id"), r.getString("asset_type"),
                        r.getString("description"), r.getBigDecimal("quantity"), r.getString("unit"),
                        r.getBigDecimal("value"), r.getString("holder_source_record_key"),
                        r.getString("linked_source_benefit_id"), Db.uuid(r, "linked_benefit_id"),
                        r.getString("linked_scheme_id"), db.json(r.getString("attributes_text")),
                        Db.time(r, "created_at"), Db.time(r, "observed_at")),
                Db.withScope(goldenResidentId));
        return new ResidentAssets(goldenResidentId, status, assets.size(), assets);
    }
}
