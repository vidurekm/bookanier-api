package online.bookanear.api;

import jakarta.validation.Validation;
import online.bookanear.api.dto.AuthDto;
import online.bookanear.api.dto.BackofficeDto;
import online.bookanear.api.dto.PasswordResetDto;
import online.bookanear.api.dto.RegistrationDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountValidationTest {
    @Test
    void emailRequiresDottedDomainAndAllowsCommonAddresses() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String invalid : new String[]{"df@s", "test@localhost", "test@example.c", "test@.cz", "no-at-sign"}) {
                assertThat(validator.validate(new RegistrationDto.RegisterRequest(
                        "Studio", "Owner", invalid, "12345678")))
                        .as("registration must reject %s", invalid).isNotEmpty();
                assertThat(validator.validate(new AuthDto.LoginRequest(invalid, "anything")))
                        .as("login must reject %s", invalid).isNotEmpty();
                assertThat(validator.validate(new PasswordResetDto.Request(invalid)))
                        .as("recovery must reject %s", invalid).isNotEmpty();
            }
            for (String valid : new String[]{"df@s.cz", "john+tag@example.co.uk", "worker@example.test"}) {
                assertThat(validator.validate(new RegistrationDto.RegisterRequest(
                        "Studio", "Owner", valid, "12345678")))
                        .as("registration must accept %s", valid).isEmpty();
                assertThat(validator.validate(new PasswordResetDto.Request(valid)))
                        .as("recovery must accept %s", valid).isEmpty();
            }
            assertThat(validator.validate(new RegistrationDto.RegisterRequest(
                    "Studio", "Owner", "owner@example.cz", "12345678", "de"))).isNotEmpty();
            for (String language : new String[]{"cs", "sk", "pl", "en"}) {
                assertThat(validator.validate(new RegistrationDto.RegisterRequest(
                        "Studio", "Owner", "owner@example.cz", "12345678", language))).isEmpty();
            }
        }
    }

    @Test
    void optionalCustomerEmailCanBeEmptyButNotMalformed() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new BackofficeDto.CreateCustomer("Jane", null, null))).isEmpty();
            assertThat(validator.validate(new BackofficeDto.CreateCustomer("Jane", "", null))).isEmpty();
            assertThat(validator.validate(new BackofficeDto.CreateCustomer("Jane", "df@s", null))).isNotEmpty();
            assertThat(validator.validate(new BackofficeDto.CreateCustomer("Jane", "jane@example.cz", null))).isEmpty();
        }
    }

    @Test
    void registrationAndPasswordResetRequireEightTo128Characters() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int length : new int[]{7, 129}) {
                String password = "x".repeat(length);
                assertThat(validator.validate(new RegistrationDto.RegisterRequest(
                        "Studio", "Owner", "owner@example.cz", password))).isNotEmpty();
                assertThat(validator.validate(new PasswordResetDto.Confirm("a".repeat(32), password))).isNotEmpty();
            }
            for (int length : new int[]{8, 12, 128}) {
                String password = "x".repeat(length);
                assertThat(validator.validate(new RegistrationDto.RegisterRequest(
                        "Studio", "Owner", "owner@example.cz", password))).isEmpty();
                assertThat(validator.validate(new PasswordResetDto.Confirm("a".repeat(32), password))).isEmpty();
            }
        }
    }
}
