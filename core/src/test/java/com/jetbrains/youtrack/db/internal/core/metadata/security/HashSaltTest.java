package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.internal.core.security.SecurityManager;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the salt + hash of passwords.
 */
public class HashSaltTest {

  @Test
  public void testSalt() throws InvalidKeySpecException, NoSuchAlgorithmException {
    final var password = "YouTrackDBisCool";
    final var sm = new SecurityManager();
    final var hashed = SecurityManager.createHashWithSalt(password);

    Assert.assertTrue(SecurityManager.checkPasswordWithSalt(password, hashed));
  }
}
