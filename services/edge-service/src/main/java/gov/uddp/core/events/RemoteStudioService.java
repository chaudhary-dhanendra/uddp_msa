package gov.uddp.core.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import gov.uddp.contracts.StatePackClient;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.statepack.DatabaseStatePackRepository;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.studio.EdgeStudioOnboarding;
import gov.uddp.core.studio.StudioConflictException;
import gov.uddp.core.studio.StudioModels;
import gov.uddp.core.studio.StudioNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The edge's Onboarding Studio with statepack-service hosting it (MSA, {@code uddp.studio.remote=true}): the edge's
 * Studio API keeps its permission checks, state scope and audit, and every Studio operation behind it runs in
 * statepack-service ({@code /api/studio-operations/<operation>}, the user's name in X-UDDP-Actor), which keeps the
 * drafts, uploads, approvals and published versions. The edge answers only its own: the Studio status (its upload
 * limit and Golden data) and onboarding. "Onboard this draft" installs the approved version (verified by digest),
 * copies the uploaded file to where the pack names it, starts the run here and reports its start and end back.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnExpression("${uddp.studio.remote:false} and '${uddp.statepack.url:}' != ''")
public class RemoteStudioService {

    private static final Set<String> LOCAL = Set.of("status", "goldenDataExists", "setShapePreviews");

    private final String statepack;
    private final RemoteStatePackMessaging.Installer installer;
    private final EdgeStudioOnboarding onboarding;
    private final Path repositoryRoot;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public RemoteStudioService(@Value("${uddp.statepack.url}") String url, StatePackLoader packs,
                               DatabaseStatePackRepository documents, StatePackLifecycleService lifecycle,
                               PostgresConnectionFactory connections, EdgeStudioOnboarding onboarding,
                               StatePackProperties properties) {
        this.statepack = url;
        this.installer = new RemoteStatePackMessaging.Installer(new StatePackClient(url), packs, documents, lifecycle,
                connections);
        this.onboarding = onboarding;
        this.repositoryRoot = properties.repositoryRoot();
    }

    @Around("execution(public * gov.uddp.core.studio.StudioService.*(..))")
    public Object forward(ProceedingJoinPoint call) throws Throwable {
        Method method = ((MethodSignature) call.getSignature()).getMethod();
        if (LOCAL.contains(method.getName())) {
            return call.proceed();
        }
        if (method.getName().equals("onboard")) {
            return onboard((String) call.getArgs()[0], (String) call.getArgs()[1]);
        }
        return invoke(method.getName(), method, call.getArgs());
    }

    private StudioModels.OnboardStarted onboard(String draftId, String requestedMode) throws Exception {
        var draft = (StudioModels.Draft) invoke("draft", method("draft"), new Object[] {draftId});
        if (draft.published() == null) {
            throw new StudioConflictException("Publish the pack before onboarding it");
        }
        if (onboarding.inProgress()) {
            throw new StudioConflictException("Another onboarding run is in progress; wait for it to finish");
        }
        String version = draft.published().version();
        LoadedStatePack pack = installer.install(draft.packId(), version, null);
        var source = pack.sources().stream().filter(s -> s.sourceId().equals(draft.source().sourceId()))
                .findFirst().orElseThrow(() -> new StudioConflictException("The pack has no source "
                        + draft.source().sourceId()));
        if ("file".equals(source.sourceType()) && source.location() != null) {
            byte[] content = (byte[]) invoke("sourceFile", method("sourceFile"), new Object[] {draftId});
            Path target = repositoryRoot.resolve(source.location()).toAbsolutePath().normalize();
            if (!target.startsWith(repositoryRoot.toAbsolutePath().normalize())) {
                throw new StudioConflictException("The pack's source location is outside the repository");
            }
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        }
        var started = onboarding.start(draft.packId(), version, source.sourceId(), requestedMode);
        invoke("runStarted", method("runStarted"), new Object[] {draftId, started.runId()});
        onboarding.runInBackground(started.runId(), status -> {
            try {
                invoke("runFinished", method("runFinished"), new Object[] {draftId, started.runId(), status});
            } catch (Exception e) {
                throw new IllegalStateException("Cannot report the run's end to statepack-service", e);
            }
        });
        return started;
    }

    private static Method method(String name) {
        for (Method method : gov.uddp.core.studio.StudioService.class.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private Object invoke(String operation, Method method, Object[] args) throws Exception {
        ArrayNode body = json.createArrayNode();
        for (Object arg : args) {
            if (arg instanceof InputStream stream) {
                body.add(Base64.getEncoder().encodeToString(stream.readAllBytes()));
            } else {
                body.add(json.valueToTree(arg));
            }
        }
        String actor = CurrentUser.username();
        var request = gov.uddp.contracts.ServiceUrls.request(URI.create(gov.uddp.contracts.ServiceUrls.resolve(statepack)
                        + "/api/studio-operations/" + operation)).timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("X-UDDP-Actor", actor == null ? "platform" : actor)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String message = response.body();
            try {
                message = json.readTree(response.body()).path("message").asText(response.body());
            } catch (Exception ignored) {
                // plain text answer
            }
            throw switch (response.statusCode()) {
                case 400 -> new IllegalArgumentException(message);
                case 403 -> new AccessDeniedException(message);
                case 404 -> new StudioNotFoundException(message);
                case 409 -> new StudioConflictException(message);
                default -> new IllegalStateException("statepack-service failed the Studio operation " + operation
                        + ": " + message);
            };
        }
        if (method.getReturnType() == void.class || response.body() == null || response.body().isBlank()) {
            return null;
        }
        JsonNode answer = json.readTree(response.body());
        if (method.getReturnType() == byte[].class) {
            return Base64.getDecoder().decode(answer.asText());
        }
        return json.convertValue(answer, json.getTypeFactory().constructType(method.getGenericReturnType()));
    }
}
