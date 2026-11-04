package gov.uddp.core.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.AssetMappingEngine;
import gov.uddp.core.mapping.AssetMappingEngine.AssetMapping;
import gov.uddp.core.mapping.AssetMappingEngine.MappedAsset;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import java.sql.Connection;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes the canonical Asset observation of one raw record: the asset the delivery names and its stable platform
 * identity (created on first sight). Idempotent per raw record and State Pack version. A mapping failure is recorded
 * in {@code asset_processing_error} and never affects the Resident.
 */
@Service
public class CanonicalAssetProcessor {
    private final PostgresConnectionFactory connections;
    private final AssetMappingEngine mapper;
    private final ObjectMapper json;

    public CanonicalAssetProcessor(PostgresConnectionFactory connections, AssetMappingEngine mapper,
                                   ObjectMapper json) {
        this.connections = connections;
        this.mapper = mapper;
        this.json = json;
    }

    /** Outcome of observing one raw record. */
    public enum Outcome { OBSERVED, NO_ASSET, ALREADY_OBSERVED, FAILED }

    /** stateCode|sourceSystem|sourceEntity|sourceAssetId: an asset as one source names it. */
    public static String assetRecordKey(RawRecord raw, String sourceAssetId) {
        return String.join("|", raw.stateCode(), raw.sourceSystem(), raw.sourceEntity(), sourceAssetId);
    }

    /**
     * @param residentRecordKey   the Resident source record key of the delivery (the holder unless the pack maps one)
     * @param canonicalResidentId the canonical Resident that represents this delivery
     */
    public Outcome observe(RawRecord raw, LoadedStatePack pack, String residentRecordKey, UUID canonicalResidentId) {
        Optional<AssetMapping> mapped;
        try {
            mapped = mapper.map(raw, pack, raw.sourceSystem());
        } catch (RuntimeException error) {
            recordFailure(raw, pack.manifest().version(), error.getMessage());
            return Outcome.FAILED;
        }
        if (mapped.isEmpty() || mapped.get().asset().isEmpty()) {
            return Outcome.NO_ASSET;
        }
        MappedAsset asset = mapped.get().asset().get();
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                UUID assetId = assetIdentity(c, raw, assetRecordKey(raw, asset.sourceAssetId()), asset);
                if (!insertObservation(c, raw, pack, mapped.get(), asset, assetId, residentRecordKey,
                        canonicalResidentId)) {
                    c.rollback();
                    return Outcome.ALREADY_OBSERVED;
                }
                c.commit();
                return Outcome.OBSERVED;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot persist canonical Asset observation", e);
        }
    }

    /** The asset's platform ID: the existing one for its record key, or a new one (never reused). */
    private static UUID assetIdentity(Connection c, RawRecord raw, String key, MappedAsset asset) throws Exception {
        try (var s = c.prepareStatement("insert into asset(asset_id, asset_record_key, state_code, source_system,"
                + " source_entity, source_asset_id) values (?,?,?,?,?,?) on conflict (asset_record_key) do nothing")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, key);
            s.setString(3, raw.stateCode());
            s.setString(4, raw.sourceSystem());
            s.setString(5, raw.sourceEntity());
            s.setString(6, asset.sourceAssetId());
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("select asset_id from asset where asset_record_key = ?")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    private boolean insertObservation(Connection c, RawRecord raw, LoadedStatePack pack, AssetMapping mapped,
                                      MappedAsset a, UUID assetId, String residentRecordKey,
                                      UUID canonicalResidentId) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("extensions", a.extensions());
        String sql = "insert into asset_observation(asset_observation_id, raw_record_id, batch_id, state_code,"
                + " source_system, source_entity, state_pack_version, mapping_version, asset_id, source_asset_id,"
                + " asset_type, description, quantity, unit, value, linked_source_benefit_id,"
                + " holder_source_record_key, canonical_resident_id, attributes, metadata)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))"
                + " on conflict (raw_record_id, state_pack_version) do nothing";
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, raw.rawRecordId());
            s.setObject(3, raw.batchId());
            s.setString(4, raw.stateCode());
            s.setString(5, raw.sourceSystem());
            s.setString(6, raw.sourceEntity());
            s.setString(7, pack.manifest().version());
            s.setString(8, mapped.mappingVersion());
            s.setObject(9, assetId);
            s.setString(10, a.sourceAssetId());
            s.setString(11, a.assetType());
            s.setString(12, a.description());
            s.setObject(13, a.quantity(), Types.NUMERIC);
            s.setString(14, a.unit());
            s.setObject(15, a.value(), Types.NUMERIC);
            s.setString(16, a.linkedSourceBenefitId());
            s.setString(17, a.holderSourceRecordKey() == null ? residentRecordKey : a.holderSourceRecordKey());
            s.setObject(18, canonicalResidentId);
            s.setString(19, json.writeValueAsString(a.attributes()));
            s.setString(20, json.writeValueAsString(metadata));
            return s.executeUpdate() == 1;
        }
    }

    private void recordFailure(RawRecord raw, String packVersion, String message) {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into asset_processing_error(raw_record_id, batch_id,"
                        + " state_pack_version, error_message) values (?,?,?,?)")) {
            s.setObject(1, raw.rawRecordId());
            s.setObject(2, raw.batchId());
            s.setString(3, packVersion);
            s.setString(4, message == null ? "Asset mapping failed" : message);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record Asset processing error", e);
        }
    }
}
