package gov.uddp.core.privacy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller (or one handler) whose responses carry resident data: {@link PiiMaskingAdvice} masks them with
 * {@link PiiMasker} for every caller. Unmasked data is only available through the audited reveal endpoint.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface MaskPii {
}
