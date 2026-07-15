package com.healthtech.doctor.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OpeningHoursValidator.class)
public @interface ValidOpeningHours {
    String message() default "Opening hours contain overlapping time ranges on the same day";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
