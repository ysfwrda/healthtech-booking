package com.healthtech.doctor.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

// Same rule as patient-service (kept in sync by hand). The 72 is BCrypt's input limit, counted in bytes.
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_CHARACTERS = 8;
    private static final int MAX_BYTES = 72;

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return true;
        }
        return password.codePointCount(0, password.length()) >= MIN_CHARACTERS
                && fitsEncoder(password);
    }

    // BCrypt ignores everything after byte 72, so login refuses longer input rather than match a prefix.
    public static boolean fitsEncoder(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
