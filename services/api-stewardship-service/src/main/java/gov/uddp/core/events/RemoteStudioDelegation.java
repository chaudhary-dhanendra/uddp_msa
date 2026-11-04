package gov.uddp.core.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * The edge's Matching Studio with identity-service (MSA): every endpoint keeps its permission check, audit and PII
 * masking here (they run first, around this advice), and the call itself is forwarded to identity-service, which owns
 * the matching profiles, labels, training, monitoring and proposals; the user's name travels as X-UDDP-Actor and the
 * answer is read back as the endpoint's own return type. On only with {@code uddp.identity.url}.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnExpression("'${uddp.identity.url:}' != ''")
public class RemoteStudioDelegation {

    private final String identity;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public RemoteStudioDelegation(@Value("${uddp.identity.url}") String identity) {
        this.identity = identity;
    }

    @Around("execution(public * gov.uddp.core.api.v1.MatchingStudioController.*(..))")
    public Object forwardToIdentity(ProceedingJoinPoint call) throws Exception {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        Method method = ((MethodSignature) call.getSignature()).getMethod();
        Object body = null;
        var annotations = method.getParameterAnnotations();
        for (int i = 0; i < annotations.length; i++) {
            for (var annotation : annotations[i]) {
                if (annotation instanceof RequestBody) {
                    body = call.getArgs()[i];
                }
            }
        }
        String target = request.getRequestURI().substring(request.getContextPath().length())
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var builder = HttpRequest.newBuilder(URI.create(gov.uddp.contracts.ServiceUrls.resolve(identity) + target)).timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("X-UDDP-Actor", authentication == null ? "system" : authentication.getName())
                .method(request.getMethod(), body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String message = response.body();
            try {
                message = json.readTree(response.body()).path("message").asText(response.body());
            } catch (Exception ignored) {
                // plain text answer
            }
            throw new ResponseStatusException(HttpStatus.valueOf(response.statusCode()), message);
        }
        if (method.getReturnType() == void.class || response.body() == null || response.body().isBlank()) {
            return null;
        }
        JavaType type = json.getTypeFactory().constructType(method.getGenericReturnType());
        return json.readValue(response.body(), type);
    }
}
