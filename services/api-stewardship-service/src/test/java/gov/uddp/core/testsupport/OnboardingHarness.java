package gov.uddp.core.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.OnboardingService;
import gov.uddp.core.onboarding.PythonMatchingRunner;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.statepack.StatePackLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Wires a real {@link OnboardingService} against a disposable database with the two process boundaries replaced:
 * Hop by {@link HopShapedRawLoader#landInto} (what the generic pipeline writes) and Python/Splink by
 * {@link MatchingRunFixture} (what {@code matching/run.py} persists). Both are generic: everything comes from the
 * State Pack configuration. The real boundaries run in {@code scripts/reset-and-run-*-e2e}.
 */
public final class OnboardingHarness {
    public final PostgresConnectionFactory connections;
    public final StatePackLoader packs;
    public final StatePackLifecycleService lifecycle;
    public final OnboardingService onboarding;
    /** Number of times the generic Hop boundary was invoked. */
    public final AtomicInteger hopCalls = new AtomicInteger();
    /** Number of times the matching boundary was invoked. */
    public final AtomicInteger matchingCalls = new AtomicInteger();
    /** Generic failure hook for the matching boundary: a non-null message makes the next call fail with it. */
    public volatile String failMatchingWith;
    /** Generic hook run inside the matching boundary before it does any work (used to hold a stage in flight). */
    public volatile Runnable beforeMatching = () -> { };
    /** AUTO_MATCH groups the matching boundary reports, computed when it runs (default: none). */
    public volatile java.util.function.Supplier<java.util.Collection<java.util.Set<UUID>>> autoMatches = List::of;

    public OnboardingHarness(Path statePackDirectory) {
        this(TestDatabase.freshMigrated(), statePackDirectory);
    }

    public OnboardingHarness(PostgresConnectionFactory connections, Path statePackDirectory) {
        this.connections = connections;
        this.packs = SampleData.loader(statePackDirectory);
        this.lifecycle = new StatePackLifecycleService(connections, packs);
        var raw = new HopShapedRawLoader(connections, SampleData.REPOSITORY_ROOT);
        HopRunner hop = (source, stateCode, version, batch) -> {
            hopCalls.incrementAndGet();
            int rows = raw.landInto(source, stateCode, version, batch);
            return new HopRunner.Result(0, "fixture transport landed " + rows);
        };
        var matching = new PythonMatchingRunner(new ObjectMapper()) {
            @Override
            public UUID run() {
                matchingCalls.incrementAndGet();
                beforeMatching.run();
                String failure = failMatchingWith;
                if (failure != null) {
                    throw new IllegalStateException(failure);
                }
                return MatchingRunFixture.completed(connections, autoMatches.get());
            }

            @Override
            public UUID runIncremental() {
                matchingCalls.incrementAndGet();
                beforeMatching.run();
                String failure = failMatchingWith;
                if (failure != null) {
                    throw new IllegalStateException(failure);
                }
                return MatchingRunFixture.incremental(connections, autoMatches.get());
            }
        };
        this.onboarding = new OnboardingService(connections, lifecycle, packs, hop,
                SampleData.processing(connections, packs), matching, SampleData.golden(connections, packs));
    }

    /** Registers the pack and moves it to ACTIVE so runs may start. */
    public OnboardingHarness activate(String packId, String version) {
        lifecycle.register(packId);
        lifecycle.transition(packId, version, "VALIDATED");
        lifecycle.transition(packId, version, "ACTIVE");
        return this;
    }

    public <T> T query(String sql, Function<java.sql.ResultSet, T> reader, Object... parameters) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (var r = s.executeQuery()) {
                return reader.apply(r);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int count(String sql, Object... parameters) {
        return query(sql, r -> {
            try {
                r.next();
                return r.getInt(1);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }, parameters);
    }
}
