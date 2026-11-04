package gov.uddp.statepack.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.studio.StudioConflictException;
import gov.uddp.core.studio.StudioNotFoundException;
import gov.uddp.core.studio.StudioService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Studio's operations for the edge (MSA): the edge keeps its Studio API with its permission checks, state scope
 * and audit, and calls the Studio here, one operation per {@link StudioService} method, its arguments as a JSON array
 * (an InputStream travels as base64) and the caller's name in X-UDDP-Actor. Only the Studio's public operations are
 * callable; the service listens on the loopback address unless deployed behind the platform's network policy.
 */
@RestController
public class StudioRpcController {

    /** Operations the edge answers itself (its onboarding runs and Golden data). */
    private static final Set<String> EDGE_ONLY = Set.of("onboard", "goldenDataExists", "setShapePreviews");

    private final StudioService studio;
    private final ObjectMapper json;
    private final Map<String, Method> operations;

    public StudioRpcController(StudioService studio, ObjectMapper json) {
        this.studio = studio;
        this.json = json;
        this.operations = Arrays.stream(StudioService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !EDGE_ONLY.contains(m.getName()))
                .collect(Collectors.toMap(Method::getName, Function.identity()));
    }

    @PostMapping("/api/studio-operations/{operation}")
    public ResponseEntity<Object> call(@PathVariable String operation, @RequestBody(required = false) JsonNode args)
            throws Throwable {
        Method method = operations.get(operation);
        if (method == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Unknown Studio operation: "
                    + operation));
        }
        var types = method.getGenericParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            JsonNode arg = args == null ? null : args.get(i);
            if (arg == null || arg.isNull()) {
                values[i] = null;
            } else if (method.getParameterTypes()[i] == InputStream.class) {
                values[i] = new ByteArrayInputStream(Base64.getDecoder().decode(arg.asText()));
            } else {
                values[i] = json.convertValue(arg, json.getTypeFactory().constructType(types[i]));
            }
        }
        try {
            Object result = method.invoke(studio, values);
            if (method.getReturnType() == void.class) {
                return ResponseEntity.noContent().build();
            }
            // Bytes travel as a base64 JSON string (as an InputStream argument does).
            return ResponseEntity.ok(result instanceof byte[] bytes ? json.getNodeFactory().textNode(
                    Base64.getEncoder().encodeToString(bytes)) : result);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @ExceptionHandler(StudioNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(StudioNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(StudioConflictException.class)
    ResponseEntity<Map<String, String>> conflict(StudioConflictException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, Exception e) {
        return ResponseEntity.status(status).body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
