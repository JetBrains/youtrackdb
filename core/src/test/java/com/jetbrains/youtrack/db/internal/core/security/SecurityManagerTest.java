package com.jetbrains.youtrack.db.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 *
 */
public class SecurityManagerTest {

  @Test
  public void shouldCheckPlainPasswordAgainstHash() throws Exception {

    var hash = SecurityManager.createHash("password", SecurityManager.HASH_ALGORITHM, true);

    assertThat(SecurityManager.checkPassword("password", hash)).isTrue();

    hash = SecurityManager.createHash("password", SecurityManager.PBKDF2_ALGORITHM, true);

    assertThat(SecurityManager.checkPassword("password", hash)).isTrue();
  }

  @Test
  public void shouldCheckHashedPasswordAgainstHash() throws Exception {

    var hash = SecurityManager.createHash("password", SecurityManager.HASH_ALGORITHM, true);
    assertThat(SecurityManager.checkPassword(hash, hash)).isFalse();

    hash = SecurityManager.createHash("password", SecurityManager.PBKDF2_ALGORITHM, true);

    assertThat(SecurityManager.checkPassword(hash, hash)).isFalse();
  }

  @Test
  public void shouldCheckPlainPasswordAgainstHashWithSalt() throws Exception {

    var hash = SecurityManager.createHashWithSalt("password");

    assertThat(SecurityManager.checkPasswordWithSalt("password", hash)).isTrue();
  }

  @Test
  public void shouldCheckHashedWithSalPasswordAgainstHashWithSalt() throws Exception {

    var hash = SecurityManager.createHashWithSalt("password");
    assertThat(SecurityManager.checkPasswordWithSalt(hash, hash)).isFalse();
  }
}
