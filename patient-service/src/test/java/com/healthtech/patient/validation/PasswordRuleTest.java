package com.healthtech.patient.validation;

import com.healthtech.patient.domain.InsuranceType;
import com.healthtech.patient.dto.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// The register request's password rule against the real BCrypt encoder: whatever it accepts must be hashable.
class PasswordRuleTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static RegisterRequest requestWith(String password) {
        return RegisterRequest.builder()
                .firstName("Max").lastName("Mustermann").username("max.mustermann")
                .password(password)
                .dateOfBirth(LocalDate.of(1990, 5, 17))
                .email("max.mustermann@example.com")
                .insuranceType(InsuranceType.STATUTORY)
                .build();
    }

    private static boolean passwordRejected(String password) {
        return VALIDATOR.validate(requestWith(password)).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password"));
    }

    static Stream<Arguments> bytesAroundTheLimit() {
        return Stream.of(
                Arguments.of("72 ASCII characters", "a".repeat(72), false),
                Arguments.of("73 ASCII characters", "a".repeat(73), true),
                Arguments.of("36 two-byte characters (72 bytes)", "ä".repeat(36), false),
                Arguments.of("37 two-byte characters (74 bytes)", "ä".repeat(37), true),
                Arguments.of("24 three-byte characters (72 bytes)", "€".repeat(24), false),
                Arguments.of("25 three-byte characters (75 bytes)", "€".repeat(25), true),
                Arguments.of("18 four-byte characters (72 bytes)", "😀".repeat(18), false),
                Arguments.of("19 four-byte characters (76 bytes)", "😀".repeat(19), true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bytesAroundTheLimit")
    void password_isRefusedExactlyWhenTheEncoderCannotHashIt(String description, String password, boolean refused) {
        // Act
        boolean rejected = passwordRejected(password);

        // Assert
        assertThat(rejected).isEqualTo(refused);
        if (!rejected) {
            assertThatCode(() -> ENCODER.encode(password)).doesNotThrowAnyException();
        }
    }

    @Test
    void password_sevenCharacters_isRefused() {
        // Act & Assert
        assertThat(passwordRejected("1234567")).isTrue();
    }

    @Test
    void password_eightCharacters_isAccepted() {
        // Act & Assert
        assertThat(passwordRejected("12345678")).isFalse();
    }

    @Test
    void password_sevenCharactersOfFourBytesEach_isRefused() {
        // Act & Assert: 14 UTF-16 code units, but only 7 characters
        assertThat(passwordRejected("😀".repeat(7))).isTrue();
    }

    @Test
    void password_eightCharactersOfFourBytesEach_isAccepted() {
        // Act & Assert: 32 bytes
        assertThat(passwordRejected("😀".repeat(8))).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bytesAroundTheLimit")
    void fitsEncoder_isTrueExactlyUpTo72Bytes(String description, String password, boolean refused) {
        // Act & Assert: the login guard counts bytes, like registration
        assertThat(PasswordValidator.fitsEncoder(password)).isEqualTo(!refused);
    }
}
