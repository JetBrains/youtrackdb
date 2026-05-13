package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests salted password hashing — the core round-trip property the production code relies on
 * (a stored hash plus its salt and iteration count must verify the original plaintext, and
 * any other plaintext must fail). Iterations are kept low (100) per round-trip so the tests
 * stay fast; production uses 65 536 by default.
 */
public class HashSaltTest {

  @Test
  public void testSalt() {
    // Default algorithm path (PBKDF2WithHmacSHA256 per GlobalConfiguration default).
    final var password = "YouTrackDBisCool";
    final var hashed = SecurityManager.createHashWithSalt(password);
    Assert.assertTrue(SecurityManager.checkPasswordWithSalt(password, hashed));
  }

  @Test
  public void testSaltExplicitSha1Algorithm() {
    // PBKDF2WithHmacSHA1 explicit overload; round-trip verifies the algorithm parameter is
    // honoured by both encoder and verifier.
    final var password = "secret";
    final var hashed =
        SecurityManager.createHashWithSalt(password, 100, SecurityManager.PBKDF2_ALGORITHM);
    Assert.assertTrue(
        SecurityManager.checkPasswordWithSalt(password, hashed, SecurityManager.PBKDF2_ALGORITHM));
  }

  @Test
  public void testSaltExplicitSha256Algorithm() {
    // PBKDF2WithHmacSHA256 explicit overload.
    final var password = "secret";
    final var hashed =
        SecurityManager.createHashWithSalt(
            password, 100, SecurityManager.PBKDF2_SHA256_ALGORITHM);
    Assert.assertTrue(
        SecurityManager.checkPasswordWithSalt(
            password, hashed, SecurityManager.PBKDF2_SHA256_ALGORITHM));
  }

  @Test
  public void testSaltRejectsWrongPassword() {
    final var hashed = SecurityManager.createHashWithSalt("password");
    Assert.assertFalse(SecurityManager.checkPasswordWithSalt("wrong-password", hashed));
  }

  @Test
  public void testSaltStoresRecoverableHashShape() {
    // Encoded format is "<hexHash>:<hexSalt>:<iterations>" — split on ':' gives 3 parts.
    final var hashed =
        SecurityManager.createHashWithSalt("password", 100, SecurityManager.PBKDF2_ALGORITHM);
    final var parts = hashed.split(":");
    Assert.assertEquals(3, parts.length);
    Assert.assertEquals("100", parts[2]);
    // Hash and salt are upper-case hex strings of size HASH_SIZE * 2 / SALT_SIZE * 2.
    Assert.assertEquals(SecurityManager.HASH_SIZE * 2, parts[0].length());
    Assert.assertEquals(SecurityManager.SALT_SIZE * 2, parts[1].length());
  }
}
