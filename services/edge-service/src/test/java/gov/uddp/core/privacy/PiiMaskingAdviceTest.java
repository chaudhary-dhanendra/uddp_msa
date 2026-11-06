package gov.uddp.core.privacy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class PiiMaskingAdviceTest {
    @MaskPii
    static class MaskedController {
        public String residents() {
            return "";
        }

        @MaskPii(false)
        public String profile() {
            return "";
        }
    }

    static class PlainController {
        public String settings() {
            return "";
        }

        @MaskPii
        public String resident() {
            return "";
        }
    }

    private final PiiMaskingAdvice advice = new PiiMaskingAdvice(new ObjectMapper(), null, List.of());

    @Test
    void aMaskedControllerMasksEveryHandlerExceptThoseThatOptOut() throws Exception {
        assertTrue(masks(MaskedController.class, "residents"));
        assertFalse(masks(MaskedController.class, "profile"), "a matching profile's name is not a resident's");
    }

    @Test
    void anUnmaskedControllerMasksOnlyMarkedHandlers() throws Exception {
        assertFalse(masks(PlainController.class, "settings"));
        assertTrue(masks(PlainController.class, "resident"));
    }

    private boolean masks(Class<?> controller, String method) throws Exception {
        return advice.supports(new MethodParameter(controller.getMethod(method), -1),
                MappingJackson2HttpMessageConverter.class);
    }
}
