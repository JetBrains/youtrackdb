package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests password hashing and verification using supported hash algorithms, plus the public
 * helper API (createHash variants, hex conversion, salted PBKDF2 round-trips). The
 * {@code @BeforeClass} hook lowers PBKDF2 iterations to keep the suite fast: the production
 * default is 65 536 iterations, which would make every PBKDF2 round-trip in this class
 * cost tens of milliseconds; 100 iterations is still meaningful for a round-trip property
 * test but runs in microseconds. {@code @AfterClass} restores the original value.
 *
 * <p>Tagged {@link SequentialTest} because {@code @BeforeClass} mutates the JVM-global
 * {@link GlobalConfiguration#SECURITY_USER_PASSWORD_SALT_ITERATIONS} slot for the whole
 * class lifetime. Surefire runs {@code parallel=classes} with {@code threadCountClasses=4};
 * sibling test classes (e.g. {@code HashSaltTest}, {@code DefaultPasswordAuthenticatorTest})
 * call {@link SecurityManager#createHashWithSalt} which reads the slot at runtime and
 * would observe the polluted iteration count during the parallel-class window without this
 * category. Matches the discipline already applied on
 * {@code SecurityManagerNewCredentialInterceptorDeadCodeTest} for the same JVM-global-mutation
 * reason.
 */
@Category(SequentialTest.class)
public class SecurityManagerTest {

  private static int originalIterations;

  @BeforeClass
  public static void lowerIterations() {
    originalIterations =
        GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_ITERATIONS.getValueAsInteger();
    GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_ITERATIONS.setValue(100);
  }

  @AfterClass
  public static void restoreIterations() {
    GlobalConfiguration.SECURITY_USER_PASSWORD_SALT_ITERATIONS.setValue(originalIterations);
  }

  @Test
  public void shouldCheckPlainPasswordAgainstHash() throws Exception {
    // Hash routed through SHA-256 prefix recognized by checkPassword().
    var hash = SecurityManager.createHash("password", SecurityManager.HASH_ALGORITHM, true);
    assertThat(SecurityManager.checkPassword("password", hash)).isTrue();

    // Hash routed through PBKDF2 prefix recognized by checkPassword().
    hash = SecurityManager.createHash("password", SecurityManager.PBKDF2_ALGORITHM, true);
    assertThat(SecurityManager.checkPassword("password", hash)).isTrue();
  }

  @Test
  public void shouldRejectMismatchedPassword() {
    // Comparing the produced hash string against itself must fail — the verifier expects the
    // *plaintext* on the left and the hash on the right; any other shape must return false.
    var hash = SecurityManager.createHash("password", SecurityManager.HASH_ALGORITHM, true);
    assertThat(SecurityManager.checkPassword(hash, hash)).isFalse();

    hash = SecurityManager.createHash("password", SecurityManager.PBKDF2_ALGORITHM, true);
    assertThat(SecurityManager.checkPassword(hash, hash)).isFalse();
  }

  @Test
  public void shouldCheckPlainPasswordAgainstPbkdf2WithSalt() {
    // createHashWithSalt uses the configured default algorithm (PBKDF2WithHmacSHA256).
    var hash = SecurityManager.createHashWithSalt("password");
    assertThat(SecurityManager.checkPasswordWithSalt("password", hash)).isTrue();
  }

  @Test
  public void shouldRejectMismatchedSaltedPassword() {
    var hash = SecurityManager.createHashWithSalt("password");
    assertThat(SecurityManager.checkPasswordWithSalt(hash, hash)).isFalse();
  }

  @Test
  public void shouldRoundTripPbkdf2Sha1Algorithm() {
    // PBKDF2WithHmacSHA1 path explicitly — exercises the createHashWithSalt(input, iters, alg)
    // overload and the corresponding checkPasswordWithSalt(input, hash, alg) overload.
    var hash =
        SecurityManager.createHashWithSalt("topsecret", 100, SecurityManager.PBKDF2_ALGORITHM);
    assertThat(SecurityManager.checkPasswordWithSalt("topsecret", hash,
        SecurityManager.PBKDF2_ALGORITHM)).isTrue();
    assertThat(SecurityManager.checkPasswordWithSalt("wrong", hash,
        SecurityManager.PBKDF2_ALGORITHM)).isFalse();
  }

  @Test
  public void shouldRoundTripPbkdf2Sha256Algorithm() {
    // PBKDF2WithHmacSHA256 path — same shape but exercises the SHA256 SecretKeyFactory branch
    // in getPbkdf2.
    var hash =
        SecurityManager.createHashWithSalt(
            "topsecret", 100, SecurityManager.PBKDF2_SHA256_ALGORITHM);
    assertThat(SecurityManager.checkPasswordWithSalt("topsecret", hash,
        SecurityManager.PBKDF2_SHA256_ALGORITHM)).isTrue();
    assertThat(SecurityManager.checkPasswordWithSalt("wrong", hash,
        SecurityManager.PBKDF2_SHA256_ALGORITHM)).isFalse();
  }

  @Test
  public void shouldRouteHashByAlgorithmPrefix() {
    // checkPassword distinguishes algorithms by the {ALG} prefix on the stored hash.
    final var pbkdf2Hash =
        SecurityManager.createHash("p", SecurityManager.PBKDF2_ALGORITHM, true);
    assertThat(pbkdf2Hash).startsWith(SecurityManager.PBKDF2_ALGORITHM_PREFIX);

    final var pbkdf2Sha256Hash =
        SecurityManager.createHash("p", SecurityManager.PBKDF2_SHA256_ALGORITHM, true);
    assertThat(pbkdf2Sha256Hash).startsWith(SecurityManager.PBKDF2_SHA256_ALGORITHM_PREFIX);
    assertThat(SecurityManager.checkPassword("p", pbkdf2Sha256Hash)).isTrue();

    final var sha256Hash =
        SecurityManager.createHash("p", SecurityManager.HASH_ALGORITHM, true);
    assertThat(sha256Hash).startsWith(SecurityManager.HASH_ALGORITHM_PREFIX);
    assertThat(SecurityManager.checkPassword("p", sha256Hash)).isTrue();
  }

  @Test
  public void shouldOmitAlgorithmPrefixWhenRequested() {
    // createHash(input, alg, false) returns the hash without the {ALG} prefix.
    final var hash = SecurityManager.createHash("p", SecurityManager.HASH_ALGORITHM, false);
    assertThat(hash).doesNotStartWith("{");
    assertThat(hash).hasSize(64); // SHA-256 -> 32 bytes -> 64 hex chars
  }

  @Test
  public void shouldRejectNullInput() {
    // Both null guards on createHash(input, alg, includeAlg).
    assertThatThrownBy(
        () -> SecurityManager.createHash(null, SecurityManager.HASH_ALGORITHM, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Input string is null");

    assertThatThrownBy(() -> SecurityManager.createHash("p", null, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Algorithm is null");
  }

  @Test
  public void shouldRejectUnsupportedAlgorithm() {
    assertThatThrownBy(() -> SecurityManager.createHash("p", "MD2", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not supported");
  }

  @Test
  public void shouldRejectMalformedSaltedHash() {
    // checkPasswordWithSalt expects "<hash>:<salt>:<iterations>" — anything else is invalid.
    assertThatThrownBy(
        () -> SecurityManager.checkPasswordWithSalt("p", "no-colons-here"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not contain the requested parts");
  }

  @Test
  public void shouldWrapUnknownAlgorithmInSecurityException() {
    // getPbkdf2 wraps NoSuchAlgorithmException into a SecurityException. We exercise it
    // through createHashWithSalt rather than checkPasswordWithSalt to avoid SALT_CACHE
    // short-circuiting (the cache key omits the algorithm, so a cached result for one
    // algorithm is reused for verifies under another). A fresh password forces a cache miss.
    final var freshPwd = "miss-cache-" + System.nanoTime();
    assertThatThrownBy(
        () -> SecurityManager.createHashWithSalt(freshPwd, 100, "FAKE-ALG-DOES-NOT-EXIST"))
        .isInstanceOf(
            com.jetbrains.youtrackdb.internal.core.exception.SecurityException.class)
        .hasMessageContaining("FAKE-ALG-DOES-NOT-EXIST");
  }

  @Test
  public void shouldReturnNullForNullDigestInput() {
    // digestSHA256 documents Nullable input -> Nullable output.
    assertThat(SecurityManager.digestSHA256(null)).isNull();
  }

  @Test
  public void shouldReturnNullForNullByteArrayHexConversion() {
    assertThat(SecurityManager.byteArrayToHexStr(null)).isNull();
  }

  @Test
  public void shouldHexEncodeAllByteRanges() {
    // Cover the high-nibble (>=10) and low-nibble branches in byteArrayToHexStr.
    final var hex =
        SecurityManager.byteArrayToHexStr(new byte[] {0x00, 0x0F, (byte) 0xA1, (byte) 0xFF});
    assertThat(hex).isEqualTo("000FA1FF");
  }

  @Test
  public void shouldExposeSingletonInstance() {
    final var instance1 = SecurityManager.instance();
    final var instance2 = SecurityManager.instance();
    assertThat(instance1).isSameAs(instance2);
  }

  @Test
  public void shouldProduceStableSha256ViaCreateSHA256() {
    // SHA-256 is deterministic — same input must produce same hex string.
    final var first = SecurityManager.createSHA256("hello");
    final var second = SecurityManager.createSHA256("hello");
    assertThat(first).isEqualTo(second);
    assertThat(first).hasSize(64);
  }

  @Test
  public void shouldDifferentiateDifferentSalts() {
    // Two consecutive calls with the same password produce different hashes because the
    // salt is randomly generated; both must verify correctly.
    final var firstHash = SecurityManager.createHashWithSalt("password");
    final var secondHash = SecurityManager.createHashWithSalt("password");
    assertThat(firstHash).isNotEqualTo(secondHash);
    assertThat(SecurityManager.checkPasswordWithSalt("password", firstHash)).isTrue();
    assertThat(SecurityManager.checkPasswordWithSalt("password", secondHash)).isTrue();
  }

  @Test
  public void shouldDispatchBareHashEqualityCheck() {
    // When the stored hash carries no {ALG} prefix, checkPassword falls back to a
    // constant-time comparison of SHA-256 digests of the two inputs. Equal inputs match;
    // different inputs do not.
    assertThat(SecurityManager.checkPassword("same", "same")).isTrue();
    assertThat(SecurityManager.checkPassword("same", "different")).isFalse();
  }

  @Test
  public void shouldExposeDeterministicCreateHashTwoArgOverload() throws Exception {
    // The two-arg createHash overload produces a raw hex digest with no {ALG} prefix.
    final var hash = SecurityManager.createHash("input", SecurityManager.HASH_ALGORITHM);
    assertThat(hash).hasSize(64);
    assertThat(hash).doesNotStartWith("{");
  }

  @Test
  public void shouldDefaultToSha256WhenAlgorithmNullOnTwoArgOverload() throws Exception {
    // Two-arg createHash treats a null algorithm as the SHA-256 default.
    final var hash = SecurityManager.createHash("input", null);
    assertThat(hash).hasSize(64);
  }

  @Test
  public void saltCacheCurrentlyConfusesAlgorithmsLatentBugPin() {
    // Latent production bug: SecurityManager.SALT_CACHE keys on
    // hashedPassword|salt|iterations|bytes and OMITS the algorithm. Therefore a hash
    // produced under PBKDF2-SHA1 can be "verified" successfully under PBKDF2-SHA256
    // because the cache short-circuits on a key that does not distinguish the two
    // algorithms.
    //
    // The assertion below pins the CURRENT (buggy) observable: a hash produced under
    // PBKDF2-SHA1 verifies as TRUE under PBKDF2-SHA256 — algorithm confusion through
    // the cache. After the production fix (cache key includes the algorithm),
    // verifyUnderSha256 must become false because the cache miss forces a real
    // PBKDF2-SHA256 computation that does not match the SHA1-derived hash.
    //
    // WHEN-FIXED: Track 22 — once the cache key includes the algorithm, this
    // assertion must be flipped from .isTrue() to .isFalse().
    final var pwd = "shared-pwd-" + System.nanoTime();
    final var sha1Hash =
        SecurityManager.createHashWithSalt(pwd, 100, SecurityManager.PBKDF2_ALGORITHM);
    final boolean verifyUnderSha256 = SecurityManager.checkPasswordWithSalt(
        pwd, sha1Hash, SecurityManager.PBKDF2_SHA256_ALGORITHM);
    // WHEN-FIXED: Track 22 — flip to .isFalse() once SALT_CACHE keys include the algorithm.
    assertThat(verifyUnderSha256).isTrue();
  }

  @Test
  public void shouldLeakNumberFormatExceptionOnNonNumericIterations() {
    // Production bug class: checkPasswordWithSalt parses the iterations field with
    // Integer.parseInt and does NOT wrap NumberFormatException — the documented
    // contract is IllegalArgumentException for malformed inputs (see the
    // assertThatThrownBy on "no-colons-here" above). This test pins the current
    // observable so a future production fix that wraps NumberFormatException into
    // IllegalArgumentException flips the assertion type and signals the change.
    //
    // WHEN-FIXED: Track 22 — wrap NumberFormatException as IllegalArgumentException;
    // change the .isInstanceOf(NumberFormatException.class) below to
    // .isInstanceOf(IllegalArgumentException.class).
    assertThatThrownBy(
        () -> SecurityManager.checkPasswordWithSalt(
            "p", "deadbeef:cafebabe:not-a-number"))
        .isInstanceOf(NumberFormatException.class);
  }

  @Test
  public void shouldLeakIllegalArgumentExceptionOnNonHexHashChars() {
    // hexToByteArray throws when the hash field contains non-hex characters. The
    // call surfaces as IllegalArgumentException (StringBuilder/Integer.parseInt path)
    // — distinct from the structural "does not contain the requested parts" message.
    // We pin only that an IllegalArgumentException leaks (not the specific text)
    // because the exact message depends on the failing parse step.
    assertThatThrownBy(
        () -> SecurityManager.checkPasswordWithSalt(
            "p", "zzzz:cafebabe:100"))
        .isInstanceOf(IllegalArgumentException.class);
  }

}
