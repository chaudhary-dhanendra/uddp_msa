package gov.uddp.core.api.v1;

import static gov.uddp.core.testsupport.AssetFixture.HEADER;
import static gov.uddp.core.testsupport.AssetFixture.RULES;
import static gov.uddp.core.testsupport.AssetFixture.pack;
import static gov.uddp.core.testsupport.AssetFixture.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.uddp.core.canonical.CanonicalAssetProcessor;
import gov.uddp.core.canonical.CanonicalAssetProcessor.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Assets through the read API; synthetic deliveries reuse existing raw records and only add asset rows. */
class AssetReadApiTest extends ReadApiFixture {
    @Autowired
    CanonicalAssetProcessor processor;

    @Test
    void aResidentsAssetsAreReadWithTheirBenefitLink() throws Exception {
        UUID ram = sql().goldenOf(sql().canonicalsNamed("Ram Singh").getFirst());
        String holder = sql().text("select source_record_key from golden_resident_membership"
                + " where golden_resident_id = ? and effective_to is null order by source_record_key limit 1", ram);
        var raws = sql().column("select raw_record_id from raw_record order by raw_record_id limit 2", UUID.class);
        UUID batch = sql().uuid("select batch_id from raw_record where raw_record_id = ?", raws.getFirst());
        String state = sql().text("select state_code from raw_record where raw_record_id = ?", raws.getFirst());
        String sourceBenefit = sql().text("select source_benefit_id from benefit where state_code = ?"
                + " order by benefit_id limit 1", state);
        String[] lines = {
            "AS-1,PV,Rooftop panel,3,kw,45000," + sourceBenefit + "," + holder + ",blue,portal",
            "AS-2,COW,Two cows,2,head,,," + holder + ",,"};
        for (int i = 0; i < lines.length; i++) {
            assertEquals(Outcome.OBSERVED, processor.observe(raw(raws.get(i), batch, state, HEADER, lines[i]),
                    pack("asset-test-" + i, RULES), holder, null));
        }

        var assets = read("/api/v1/golden-residents/{id}/assets", ram);
        assertEquals(2, assets.path("assetCount").asInt());
        var list = assets.path("assets");
        var solar = list.get(0).path("assetType").asText().equals("SOLAR_INSTALLATION") ? list.get(0) : list.get(1);
        var cows = solar == list.get(0) ? list.get(1) : list.get(0);
        assertEquals("KW", solar.path("unit").asText());
        assertEquals(3, solar.path("quantity").asInt());
        assertEquals(45000, solar.path("value").asInt());
        assertEquals("blue", solar.path("attributes").path("color").asText());
        assertEquals(sourceBenefit, solar.path("linkedSourceBenefitId").asText());
        assertEquals(false, solar.path("linkedBenefitId").isNull(), "the benefit link is resolved on read");
        assertEquals("LIVESTOCK", cows.path("assetType").asText());
        assertEquals("HEAD", cows.path("unit").asText());

        UUID other = sql().goldenOf(sql().canonicalsNamed("Sunita Devi").getFirst());
        assertEquals(0, read("/api/v1/golden-residents/{id}/assets", other).path("assetCount").asInt());
        mvc.perform(get("/api/v1/golden-residents/{id}/assets", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
