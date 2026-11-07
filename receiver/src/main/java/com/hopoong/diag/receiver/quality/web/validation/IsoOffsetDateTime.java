package com.hopoong.diag.receiver.quality.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IsoOffsetDateTimeValidator.class)
public @interface IsoOffsetDateTime {
    String message() default "must be an ISO offset date-time";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
