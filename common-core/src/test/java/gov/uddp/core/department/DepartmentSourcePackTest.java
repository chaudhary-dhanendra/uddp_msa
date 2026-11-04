package gov.uddp.core.department;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.department.DepartmentRepository.StateProfile;
import gov.uddp.core.department.SourcePackService.DepartmentWithSources;
import gov.uddp.core.department.SourcePackService.SourcePack;
import gov.uddp.core.onboarding.OnboardingStage;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.statepack.StatePackManifest;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** R1a: the existing packs are the Source Packs of departments DEPT-1..DEPT-4 and onboard exactly as before. */
class DepartmentSourcePackTest {

    @Test
    void existingPacksLoadAsSourcePacksOfTheirDepartments() {
        var connections = TestDatabase.freshMigrated();
        var repository = new DepartmentRepository(connections);
        var service = new SourcePackService(SampleData.loader(SampleData.STATE_PACKS), repository);

        Map<String, String> departmentByPack = service.all().stream()
                .collect(Collectors.toMap(SourcePack::packId, SourcePack::departmentCode));
        assertEquals(Map.of("state-1", "DEPT-1", "state-2", "DEPT-2", "state-3", "DEPT-3"), departmentByPack);

        List<DepartmentWithSources> departments = service.departments();
        assertEquals(List.of("DEPT-1", "DEPT-2", "DEPT-3", "DEPT-4"),
                departments.stream().map(DepartmentWithSources::departmentCode).toList());
        SourcePack pds = departments.getFirst().sourcePacks().getFirst();
        assertEquals("state-1", pds.packId());
        assertEquals("ST1", pds.sourceCode(), "the old state code stays the pack's source code");
        assertEquals(List.of("pds"), pds.sourceIds());
        assertTrue(departments.get(3).sourcePacks().isEmpty(), "State 4 lives only in m10-state-packs");
    }

    @Test
    void aPackWithoutDepartmentCodeIsAssignedFromTheSeedTable() {
        var connections = TestDatabase.freshMigrated();
        var stripped = new StrippedLoader(SampleData.loader(SampleData.REPOSITORY_ROOT.resolve("m10-state-packs")));
        var service = new SourcePackService(stripped, new DepartmentRepository(connections));

        assertEquals("DEPT-4", service.find("state-4").orElseThrow().departmentCode());
        assertEquals("DEPT-4", service.find("ST4").orElseThrow().departmentCode());
    }

    @Test
    void theStateProfileIsStoredOncePerDeployment() {
        var repository = new DepartmentRepository(TestDatabase.freshMigrated());
        assertTrue(repository.stateProfile().isEmpty());

        repository.saveStateProfile(new StateProfile("XX", "Example State", "99", List.of("en")));
        repository.saveStateProfile(new StateProfile("XX", "Example State", "99", List.of("en", "hi")));

        assertEquals(new StateProfile("XX", "Example State", "99", List.of("en", "hi")),
                repository.stateProfile().orElseThrow());
    }

    @Test
    void onboardingADepartmentSourceProducesTheSameCounts() {
        var harness = new OnboardingHarness(SampleData.REPOSITORY_ROOT.resolve("m10-state-packs"))
                .activate("state-4", "1.0.0");
        var service = new SourcePackService(harness.packs, new DepartmentRepository(harness.connections));
        assertEquals("DEPT-4", service.find("state-4").orElseThrow().departmentCode());

        UUID run = harness.onboarding.start("state-4", "1.0.0", "citizen-register");
        for (int i = 0; i < OnboardingStage.values().length; i++) {
            harness.onboarding.executeNext(run);
        }

        assertEquals("COMPLETED", harness.query("select status from onboarding_run where onboarding_run_id=?",
                r -> {
                    try {
                        r.next();
                        return r.getString(1);
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, run));
        assertEquals(2, harness.count("select count(*) from raw_record"));
        assertEquals(2, harness.count("select count(*) from canonical_resident"));
        assertEquals(2, harness.count("select count(*) from golden_resident"));
    }

    /** The given packs as stored documents written before manifests carried a department. */
    private static final class StrippedLoader extends StatePackLoader {
        private final StatePackLoader delegate;

        StrippedLoader(StatePackLoader delegate) {
            super(new StatePackProperties());
            this.delegate = delegate;
        }

        @Override
        public Collection<LoadedStatePack> all() {
            return delegate.all().stream().map(StrippedLoader::strip).toList();
        }

        @Override
        public java.util.Optional<LoadedStatePack> find(String state) {
            return delegate.find(state).map(StrippedLoader::strip);
        }

        private static LoadedStatePack strip(LoadedStatePack pack) {
            var m = pack.manifest();
            var manifest = new StatePackManifest(m.packId(), m.stateCode(), m.version(), m.commonModelVersion(),
                    m.status(), m.sources(), m.mappings(), m.crosswalks(), m.validation(), m.matching(),
                    m.survivorship(), m.extensions());
            return new LoadedStatePack(manifest, pack.sources(), pack.mappings(), pack.crosswalks(),
                    pack.validations(), pack.matching(), pack.survivorship());
        }
    }
}
