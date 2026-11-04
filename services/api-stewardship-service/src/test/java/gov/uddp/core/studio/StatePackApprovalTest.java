package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.security.UserAccountStore;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.testsupport.TestSecurity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P1 Part 4a, State Pack maker-checker: a data engineer's publish only submits the version (PENDING_APPROVAL, nothing
 * persisted or activated, onboarding refused, the draft frozen); the submitter cannot approve or reject it; a
 * different user with STATE_PACK_APPROVE rejects (with a reason) or approves it, and only then is the submitted
 * version persisted, ACTIVE and onboardable. Every step is audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatePackApprovalTest {
    static final Path PACKS = temporary("uddp-approval-packs");
    static final Path WORKSPACE = temporary("uddp-approval-workspace");

    static final UddpPrincipal ENGINEER = TestSecurity.principal("eve.engineer", Set.of("DATA_ENGINEER"),
            Set.of(Permission.STATE_PACK_VIEW, Permission.STATE_PACK_CREATE, Permission.STATE_PACK_EDIT,
                    Permission.SOURCE_UPLOAD, Permission.SOURCE_PROFILE, Permission.ONBOARDING_RUN),
            List.of("ALL"));
    static final UddpPrincipal ENGINEER_WHO_CAN_APPROVE = TestSecurity.principal("eve.engineer",
            Set.of("PLATFORM_ADMIN"), Set.of(Permission.STATE_PACK_VIEW, Permission.STATE_PACK_APPROVE),
            List.of("ALL"));
    static final UddpPrincipal APPROVER = TestSecurity.principal("sam.admin", Set.of("STATE_ADMIN"),
            Set.of(Permission.STATE_PACK_VIEW, Permission.STATE_PACK_APPROVE, Permission.ONBOARDING_RUN),
            List.of("ALL"));

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory approvalConnections() {
            return TestDatabase.freshMigrated();
        }

        @Bean
        RecordingStatePackService statePackService() {
            return new RecordingStatePackService();
        }
    }

    /** statepack-service as the Studio sees it (MSA): every publication and decision, in order. */
    static class RecordingStatePackService extends gov.uddp.core.events.StatePackPublisher {
        final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

        RecordingStatePackService() {
            super("http://statepack-service.invalid");
        }

        @Override
        public JsonNode publish(String maker, java.util.Map<String, String> files) {
            String manifest = files.get("state.yaml");
            calls.add("publish " + maker + " " + manifest.lines().filter(l -> l.startsWith("packId:")).findFirst()
                    .orElseThrow().substring(7).trim().replace("\"", ""));
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public JsonNode approveAndActivate(String packId, String version, String checker) {
            calls.add("activate " + packId + " " + version + " " + checker);
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public JsonNode reject(String packId, String version, String checker, String reason) {
            calls.add("reject " + packId + " " + version + " " + checker + " " + reason);
            return new ObjectMapper().createObjectNode();
        }

        List<String> about(String packId) {
            return calls.stream().filter(c -> c.contains(" " + packId)).toList();
        }
    }

    @Autowired
    RecordingStatePackService statePackService;

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) {
        registry.add("uddp.studio.packs-directory", PACKS::toString);
        registry.add("uddp.state-pack-directory", PACKS::toString);
        registry.add("uddp.studio.workspace-directory", WORKSPACE::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    UserAccountStore users;

    @Test
    void onlyStateAndPlatformAdministratorsMayApprove() {
        for (var role : users.roles()) {
            boolean approves = role.permissions().contains("STATE_PACK_APPROVE");
            assertEquals(Set.of("PLATFORM_ADMIN", "STATE_ADMIN").contains(role.roleCode()), approves, role.roleCode());
        }
    }

    @Test
    void aSubmittedVersionIsActivatedOnlyAfterADifferentUserApprovesIt() throws Exception {
        String id = readyDraft("approve-pack", "AX");
        Sql sql = new Sql(connections);

        JsonNode submitted = read(as(post("/api/studio/drafts/" + id + "/publish"), ENGINEER).andExpect(status().isOk()));
        assertEquals("PENDING_APPROVAL", submitted.path("status").asText());
        assertEquals("eve.engineer", submitted.path("approval").path("submittedBy").asText());
        assertTrue(submitted.path("published").isNull(), "nothing is published yet");
        String approval = submitted.path("approval").path("approvalId").asText();
        assertEquals(0, sql.count("select count(*) from state_pack_document where pack_id = 'approve-pack'"));
        assertEquals(0, sql.count("select count(*) from state_pack_lifecycle where pack_id = 'approve-pack'"));

        // Onboarding is refused until the version is approved, and the draft cannot change meanwhile.
        as(post("/api/studio/drafts/" + id + "/onboard"), ENGINEER).andExpect(status().isConflict());
        as(put("/api/studio/drafts/" + id + "/details").contentType(MediaType.APPLICATION_JSON)
                .content("{\"packId\":\"approve-pack\",\"stateCode\":\"AX\",\"displayName\":\"Changed\"}"), ENGINEER)
                .andExpect(status().isConflict());
        as(post("/api/studio/drafts/" + id + "/publish"), ENGINEER).andExpect(status().isConflict());

        // The submitter can approve nothing: without the permission, and not even with it.
        as(post("/api/studio/approvals/" + approval + "/approve"), ENGINEER).andExpect(status().isForbidden());
        as(post("/api/studio/approvals/" + approval + "/approve"), ENGINEER_WHO_CAN_APPROVE)
                .andExpect(status().isForbidden());
        as(post("/api/studio/approvals/" + approval + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"mine\"}"), ENGINEER_WHO_CAN_APPROVE).andExpect(status().isForbidden());
        assertEquals("PENDING_APPROVAL", sql.text("select status from state_pack_approval where approval_id = '"
                + approval + "'"));

        JsonNode pending = read(as(get("/api/studio/approvals?status=PENDING_APPROVAL"), APPROVER)
                .andExpect(status().isOk()));
        long listed = 0;
        for (JsonNode item : pending) {
            if (item.path("approvalId").asText().equals(approval)) {
                listed++;
                assertEquals("approve-pack", item.path("packId").asText());
                assertEquals("PENDING_APPROVAL", item.path("status").asText());
            }
        }
        assertEquals(1, listed, "the submission is in the pending approvals list");

        JsonNode approved = read(as(post("/api/studio/approvals/" + approval + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Reviewed\"}"), APPROVER)
                .andExpect(status().isOk()));
        assertEquals("PUBLISHED", approved.path("status").asText());
        assertEquals("1.0.0", approved.path("published").path("version").asText());
        assertEquals("APPROVED", approved.path("approval").path("status").asText());
        assertEquals("sam.admin", approved.path("approval").path("decidedBy").asText());
        assertEquals(1, sql.count("select count(*) from state_pack_document where pack_id = 'approve-pack'"));
        assertEquals("ACTIVE", sql.text("select status from state_pack_lifecycle where pack_id = 'approve-pack'"));
        as(post("/api/studio/approvals/" + approval + "/approve"), APPROVER).andExpect(status().isConflict());
        assertEquals(List.of("publish eve.engineer approve-pack", "publish eve.engineer approve-pack",
                "activate approve-pack 1.0.0 sam.admin"), statePackService.about("approve-pack"),
                "statepack-service records the maker's version and the checker's approval");

        assertEquals(1, audits("STUDIO_PUBLISH_SUBMIT", "SUCCESS", "eve.engineer", id));
        assertEquals(1, audits("STUDIO_PUBLISH_SUBMIT", "FAILURE", "eve.engineer", id), "the refused resubmission");
        assertEquals(1, audits("STATE_PACK_APPROVE", "SUCCESS", "sam.admin", approval));
        assertEquals(2, audits("STATE_PACK_APPROVE", "DENIED", "eve.engineer", approval));
    }

    @Test
    void aRejectionNeedsAReasonAndReturnsTheDraftForChanges() throws Exception {
        String id = readyDraft("reject-pack", "RX");
        Sql sql = new Sql(connections);
        String approval = read(as(post("/api/studio/drafts/" + id + "/publish"), ENGINEER).andExpect(status().isOk()))
                .path("approval").path("approvalId").asText();

        as(post("/api/studio/approvals/" + approval + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), APPROVER).andExpect(status().isBadRequest());
        JsonNode rejected = read(as(post("/api/studio/approvals/" + approval + "/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong district codes\"}"), APPROVER)
                .andExpect(status().isOk()));
        assertEquals("DRAFT", rejected.path("status").asText());
        assertEquals("REJECTED", rejected.path("approval").path("status").asText());
        assertEquals("Wrong district codes", rejected.path("approval").path("decisionReason").asText());
        assertEquals(0, sql.count("select count(*) from state_pack_document where pack_id = 'reject-pack'"));
        as(post("/api/studio/drafts/" + id + "/onboard"), ENGINEER).andExpect(status().isConflict());
        assertEquals("reject reject-pack 1.0.0 sam.admin Wrong district codes",
                statePackService.about("reject-pack").getLast());

        // Editable again, and it can be submitted once more.
        as(put("/api/studio/drafts/" + id + "/details").contentType(MediaType.APPLICATION_JSON)
                .content("{\"packId\":\"reject-pack\",\"stateCode\":\"RX\",\"displayName\":\"Fixed\"}"), ENGINEER)
                .andExpect(status().isOk());
        JsonNode again = read(as(post("/api/studio/drafts/" + id + "/publish"), ENGINEER).andExpect(status().isOk()));
        assertEquals("PENDING_APPROVAL", again.path("status").asText());
        assertFalse(again.path("approval").path("approvalId").asText().equals(approval));
        assertEquals(1, audits("STATE_PACK_REJECT", "SUCCESS", "sam.admin", approval));
        assertEquals(1, audits("STATE_PACK_REJECT", "FAILURE", "sam.admin", approval), "the reasonless rejection");
    }

    /** A draft whose preview is clean, ready to publish. */
    private String readyDraft(String packId, String stateCode) throws Exception {
        String id = read(as(post("/api/studio/drafts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"packId\":\"" + packId + "\",\"stateCode\":\"" + stateCode + "\",\"displayName\":\"Pack\"}"),
                ENGINEER).andExpect(status().isCreated())).path("draftId").asText();
        as(multipart("/api/studio/drafts/" + id + "/upload").file(new MockMultipartFile("file", "people.csv",
                "text/csv", "pid,name,sex\nP1,Asha,F\nP2,Ravi,M\n".getBytes())), ENGINEER).andExpect(status().isOk());
        JsonNode current = read(as(get("/api/studio/drafts/" + id), ENGINEER));
        ObjectNode configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "name").put("entity", "Resident").put("target", "name"))
                .add(json.createObjectNode().put("sourceField", "sex").put("entity", "Resident").put("target", "gender")
                        .put("crosswalk", "gender"));
        configuration.putArray("crosswalks").add(json.readTree(
                "{\"id\":\"gender\",\"codeSet\":\"gender\",\"entries\":[{\"sourceValue\":\"F\",\"targetValue\":\"FEMALE\"},"
                        + "{\"sourceValue\":\"M\",\"targetValue\":\"MALE\"}]}"));
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "pid");
        as(put("/api/studio/drafts/" + id + "/configuration").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(configuration)), ENGINEER).andExpect(status().isOk());
        as(get("/api/studio/drafts/" + id + "/preview"), ENGINEER).andExpect(jsonPath("$.blockingErrors").value(0));
        return id;
    }

    private long audits(String action, String result, String actor, String resourceId) {
        return new Sql(connections).count("select count(*) from app_audit_event where action = ? and result = ?"
                + " and actor_username = ? and resource_id = ?", action, result, actor, resourceId);
    }

    private ResultActions as(MockHttpServletRequestBuilder request, UddpPrincipal principal) throws Exception {
        return mvc.perform(request.with(TestSecurity.as(principal)));
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
