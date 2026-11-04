package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gov.uddp.contracts.StatePackDigest;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.statepack.DatabaseStatePackRepository;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.studio.EdgeStudioOnboarding;
import gov.uddp.core.studio.StudioConflictException;
import gov.uddp.core.studio.StudioModels;
import gov.uddp.core.studio.StudioService;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * With uddp.studio.remote the edge's Studio operations run in statepack-service: each call is forwarded with its
 * arguments (a file as base64) and the user's name, answers come back typed, and statepack-service's refusals keep
 * their meaning. Onboarding stays at the edge: the approved version is installed (verified by digest), the uploaded
 * file is copied to the location the pack names, the run is started here and its start and end are reported back.
 */
@Testcontainers
class RemoteStudioServiceTest {

    @Test
    void studioOperationsRunInStatepackServiceAndOnboardingStaysAtTheEdge() throws Exception {
        var connections = TestDatabase.freshMigrated();
        StatePackLoader loader = SampleData.loader(Files.createTempDirectory("uddp-empty-packs"));
        var documents = new DatabaseStatePackRepository(connections, new ObjectMapper().findAndRegisterModules());
        var setDatabase = StatePackLoader.class.getDeclaredMethod("setDatabase", DatabaseStatePackRepository.class);
        setDatabase.setAccessible(true);
        setDatabase.invoke(loader, documents);
        Path repository = Files.createTempDirectory("uddp-edge-repository");
        var properties = new StatePackProperties();
        properties.setRepositoryRoot(repository.toString());

        Map<String, String> pack = files(SampleData.STATE_PACKS.resolve("state-2"));
        String csv = "family_ref,name\nF1,Asha\n";
        var json = new ObjectMapper().findAndRegisterModules();
        var now = OffsetDateTime.now();
        var draft = new StudioModels.Draft("d-1", "state-2", "ST2", "Two", "1.0.0", StudioModels.DraftStatus.PUBLISHED,
                now, now, new StudioModels.Source("pension", "file", "pension.csv", "x", 10L, "family_ref", List.of(),
                null, null, null), List.of(), List.of(), null, new StudioModels.Publication("1.0.0", now,
                "statepack:state-2/1.0.0", List.of("state.yaml"), "ACTIVE"), null, null);

        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer statepack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        statepack.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(path.replace("/api/studio-operations/", "") + " " + exchange.getRequestHeaders()
                    .getFirst("X-UDDP-Actor") + " " + body);
            int status = 200;
            Object answer;
            if (path.startsWith("/api/statepacks/")) {
                answer = Map.of("packId", "state-2", "version", "1.0.0", "stateCode", "ST2", "status", "ACTIVE",
                        "digest", StatePackDigest.of(pack), "files", pack);
            } else if (path.endsWith("/states")) {
                answer = List.of();
            } else if (path.endsWith("/draft")) {
                answer = draft;
            } else if (path.endsWith("/sourceFile")) {
                answer = Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8));
            } else if (path.endsWith("/publish")) {
                status = 409;
                answer = Map.of("message", "The draft is waiting for approval");
            } else {
                answer = draft;
            }
            byte[] bytes = json.writeValueAsBytes(answer);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        statepack.start();
        try {
            EdgeStudioOnboarding onboarding = mock(EdgeStudioOnboarding.class);
            when(onboarding.start(eq("state-2"), eq("1.0.0"), eq("pension"), isNull()))
                    .thenReturn(new StudioModels.OnboardStarted("run-9", "FULL"));
            doAnswer(call -> {
                call.<Consumer<String>>getArgument(1).accept("COMPLETED");
                return null;
            }).when(onboarding).runInBackground(anyString(), any());

            var remote = new RemoteStudioService("http://127.0.0.1:" + statepack.getAddress().getPort(), loader,
                    documents, new StatePackLifecycleService(connections, loader), connections, onboarding,
                    properties);
            var factory = new AspectJProxyFactory(mock(StudioService.class));
            factory.setProxyTargetClass(true);
            factory.addAspect(remote);
            StudioService studio = factory.getProxy();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    gov.uddp.core.testsupport.TestSecurity.principal("eve.engineer", java.util.Set.of(), List.of(),
                            List.of("ALL")), null, List.of()));

            assertEquals(List.of(), studio.states());
            assertEquals("states eve.engineer []", seen.get(0));
            studio.upload("d-1", "people.csv", new ByteArrayInputStream("a,b\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals("upload eve.engineer [\"d-1\",\"people.csv\",\""
                    + Base64.getEncoder().encodeToString("a,b\n".getBytes(StandardCharsets.UTF_8)) + "\"]", seen.get(1));
            var refused = assertThrows(StudioConflictException.class, () -> studio.publish("d-1"));
            assertEquals("The draft is waiting for approval", refused.getMessage());

            seen.clear();
            var started = studio.onboard("d-1", null);
            assertEquals("run-9", started.runId());
            assertEquals(csv, Files.readString(repository.resolve("services/ingestion-service/seed/sample-data/state-2/pension.csv")),
                    "the uploaded file is where the pack names it");
            assertEquals("ACTIVE", new Sql(connections).text(
                    "select status from state_pack_lifecycle where pack_id = 'state-2' and version = '1.0.0'"));
            assertTrue(seen.contains("runStarted eve.engineer [\"d-1\",\"run-9\"]"), seen.toString());
            assertTrue(seen.contains("runFinished eve.engineer [\"d-1\",\"run-9\",\"COMPLETED\"]"), seen.toString());
        } finally {
            SecurityContextHolder.clearContext();
            statepack.stop(0);
        }
    }

    private static Map<String, String> files(Path pack) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(pack)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(pack.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return files;
    }
}
