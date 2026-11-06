package gov.uddp.core.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.Permission;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * The one place resident data is masked (P1 Part 5a): every JSON response of a {@link MaskPii} handler is turned
 * into a tree and masked by {@link PiiMasker} before it is written, whoever calls it. Services, matching,
 * survivorship and the database keep the real values.
 *
 * <p>Purpose-based full view: a caller with RESIDENT_VIEW_FULL who names one of the configured purposes in the
 * {@value #PURPOSE_HEADER} header gets this one response unmasked, and the full view is audited with the purpose and
 * the path. Without the permission or with an unknown purpose the response stays masked.
 */
@RestControllerAdvice
public class PiiMaskingAdvice implements ResponseBodyAdvice<Object> {
    public static final String PURPOSE_HEADER = "X-UDDP-View-Purpose";

    private final ObjectMapper json;
    private final AuditService audit;
    private final List<String> purposes;

    public PiiMaskingAdvice(ObjectMapper json, AuditService audit,
                            @Value("${uddp.privacy.reveal-purposes:Grievance redressal,Benefit eligibility verification,"
                                    + "Duplicate investigation,Data correction request,Legal or audit request}")
                            List<String> purposes) {
        this.json = json;
        this.audit = audit;
        this.purposes = purposes.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (!MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType)) {
            return false;
        }
        MaskPii onMethod = returnType.getMethod() == null ? null
                : AnnotatedElementUtils.findMergedAnnotation(returnType.getMethod(), MaskPii.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        MaskPii onClass = AnnotatedElementUtils.findMergedAnnotation(returnType.getContainingClass(), MaskPii.class);
        return onClass != null && onClass.value();
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        Optional<String> purpose = fullViewPurpose(request);
        if (purpose.isPresent()) {
            audit.record(AuditService.AuditEvent.of("RESIDENT_FULL_VIEW", "RESIDENT_DATA", request.getURI().getPath(),
                    null, AuditService.SUCCESS).with(AuditService.details("purpose", purpose.get())));
            return body;
        }
        JsonNode tree = json.valueToTree(body);
        return PiiMasker.mask(tree);
    }

    /** The configured purpose a full-view caller named for this request, if any. */
    private Optional<String> fullViewPurpose(ServerHttpRequest request) {
        String named = request.getHeaders().getFirst(PURPOSE_HEADER);
        if (named == null || named.isBlank() || !CurrentUser.has(Permission.RESIDENT_VIEW_FULL)) {
            return Optional.empty();
        }
        return purposes.stream().filter(p -> p.equalsIgnoreCase(named.strip())).findFirst();
    }
}
