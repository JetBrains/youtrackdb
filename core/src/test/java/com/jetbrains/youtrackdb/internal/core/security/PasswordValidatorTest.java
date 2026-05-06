package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Pins the {@link PasswordValidator} interface contract — it is an SPI extension point
 * with implementers in optional modules (e.g., enterprise security). The unit-test scope
 * here verifies the interface shape (extends {@link SecurityComponent}; declares the
 * checked-exception-throwing {@code validatePassword(String, String)} method) and that an
 * inline implementation can be wired through it correctly. Behavioural coverage of any
 * particular validator implementation lives outside this module.
 */
public class PasswordValidatorTest {

  @Test
  public void shouldDelegateValidationToImplementation() throws Exception {
    final var validator =
        new PasswordValidator() {
          @Override
          public void validatePassword(String username, String password)
              throws InvalidPasswordException {
            if (password == null || password.length() < 4) {
              throw new InvalidPasswordException("too short");
            }
          }

          @Override
          public void active() {
          }

          @Override
          public void config(
              com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
              java.util.Map<String, Object> jsonConfig,
              SecuritySystem security) {
          }

          @Override
          public void dispose() {
          }

          @Override
          public boolean isEnabled() {
            return true;
          }
        };

    // Happy path — long enough password.
    validator.validatePassword("user", "longenough");

    // Failure path — should throw checked InvalidPasswordException.
    try {
      validator.validatePassword("user", "ab");
      org.junit.Assert.fail("Expected InvalidPasswordException");
    } catch (InvalidPasswordException expected) {
      assertThat(expected.getMessage()).contains("too short");
    }
  }

  @Test
  public void shouldExtendSecurityComponentInterface() {
    // Pins the SecurityComponent inheritance — removing it would break consumers that
    // expect to call active()/dispose()/isEnabled() on a registered validator.
    assertThat(SecurityComponent.class.isAssignableFrom(PasswordValidator.class)).isTrue();
  }

  @Test
  public void shouldDeclareValidatePasswordMethodWithCheckedException() throws Exception {
    final var method =
        PasswordValidator.class.getDeclaredMethod(
            "validatePassword", String.class, String.class);
    assertThat(method.getExceptionTypes()).contains(InvalidPasswordException.class);
  }
}
