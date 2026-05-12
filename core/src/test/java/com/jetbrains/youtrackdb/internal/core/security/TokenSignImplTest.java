package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import java.util.Base64;
import org.junit.Test;

/**
 * Tests {@link TokenSignImpl} HMAC sign/verify round-trip with synthesized keys.
 *
 * <p>Construction strategy: instead of reaching for a concrete {@link Token} implementation
 * (which would pull in DatabaseSession plumbing), we build a minimal anonymous {@link Token}
 * stub — TokenSignImpl only needs the {@link Token#getHeader()} call site, and the default
 * {@link DefaultKeyProvider} ignores the header content entirely (it returns a single
 * SecretKeySpec regardless), so the stub may return any TokenHeader.
 */
public class TokenSignImplTest {

  /**
   * Returns a non-null but empty Token implementation. Only {@code getHeader()} is exercised
   * during sign/verify; every other method may safely throw — but JUnit must never see those
   * exceptions because TokenSignImpl never calls them.
   */
  private static Token stubToken(final TokenHeader header) {
    return new Token() {
      @Override
      public TokenHeader getHeader() {
        return header;
      }

      @Override
      public boolean getIsVerified() {
        return false;
      }

      @Override
      public void setIsVerified(boolean verified) {
      }

      @Override
      public boolean getIsValid() {
        return false;
      }

      @Override
      public void setIsValid(boolean valid) {
      }

      @Override
      public String getUserName() {
        return null;
      }

      @Override
      public com.jetbrains.youtrackdb.internal.core.security.SecurityUser getUser(
          DatabaseSessionEmbedded session) {
        return null;
      }

      @Override
      public String getDatabaseName() {
        return null;
      }

      @Override
      public String getDatabaseType() {
        return null;
      }

      @Override
      public RID getUserId() {
        return null;
      }

      @Override
      public long getExpiry() {
        return 0L;
      }

      @Override
      public void setExpiry(long expiry) {
      }

      @Override
      public boolean isNowValid() {
        return false;
      }

      @Override
      public boolean isCloseToExpire() {
        return false;
      }
    };
  }

  /**
   * Returns a minimal TokenHeader; DefaultKeyProvider does not inspect any field, so all
   * accessors may return their backing-field defaults.
   */
  private static TokenHeader stubHeader() {
    return new TokenHeader() {
      private String alg;
      private String typ;
      private String kid;

      @Override
      public String getAlgorithm() {
        return alg;
      }

      @Override
      public void setAlgorithm(String alg) {
        this.alg = alg;
      }

      @Override
      public String getType() {
        return typ;
      }

      @Override
      public void setType(String typ) {
        this.typ = typ;
      }

      @Override
      public String getKeyId() {
        return kid;
      }

      @Override
      public void setKeyId(String kid) {
        this.kid = kid;
      }
    };
  }

  /** Build a 32-byte HMAC-SHA256 key from a known seed so tests are deterministic. */
  private static byte[] testKey() {
    final var key = new byte[32];
    for (var i = 0; i < key.length; i++) {
      key[i] = (byte) (0xA0 ^ i);
    }
    return key;
  }

  @Test
  public void shouldRoundTripSignAndVerify() {
    // Synthesize a known key + payload, sign, construct ParsedToken, verify -> true.
    final var sign = new TokenSignImpl(testKey(), TokenSignImpl.ENCRYPTION_ALGORITHM_DEFAULT);
    final var header = stubHeader();
    final var payload = "the-token-payload".getBytes();

    final var signature = sign.signToken(header, payload);
    assertThat(signature).isNotEmpty();

    final var parsed = new ParsedToken(stubToken(header), payload, signature);
    assertThat(sign.verifyTokenSign(parsed)).isTrue();
  }

  @Test
  public void shouldRejectMutatedSignature() {
    // Same setup, but mutate one signature byte -> verify must return false.
    final var sign = new TokenSignImpl(testKey(), TokenSignImpl.ENCRYPTION_ALGORITHM_DEFAULT);
    final var header = stubHeader();
    final var payload = "the-token-payload".getBytes();

    final var signature = sign.signToken(header, payload);
    signature[0] ^= (byte) 0xFF; // flip every bit of the first byte

    final var parsed = new ParsedToken(stubToken(header), payload, signature);
    assertThat(sign.verifyTokenSign(parsed)).isFalse();
  }

  @Test
  public void shouldRejectMutatedPayload() {
    // The signature is over the payload; changing the payload after signing must fail
    // verification.
    final var sign = new TokenSignImpl(testKey(), TokenSignImpl.ENCRYPTION_ALGORITHM_DEFAULT);
    final var header = stubHeader();
    final var payload = "original-payload".getBytes();

    final var signature = sign.signToken(header, payload);

    final var tampered = "tampered-payload".getBytes();
    final var parsed = new ParsedToken(stubToken(header), tampered, signature);
    assertThat(sign.verifyTokenSign(parsed)).isFalse();
  }

  @Test
  public void shouldExposeAlgorithmAndKeysFromDefaultKeyProvider() {
    final var sign = new TokenSignImpl(testKey(), TokenSignImpl.ENCRYPTION_ALGORITHM_DEFAULT);

    assertThat(sign.getAlgorithm()).isEqualTo(TokenSignImpl.ENCRYPTION_ALGORITHM_DEFAULT);
    assertThat(sign.getDefaultKey()).isEqualTo("default");
    assertThat(sign.getKeys()).containsExactly("default");
  }

  @Test
  public void shouldDefaultToHmacSha256WhenAlgorithmNull() {
    // The two-arg constructor accepts a null algorithm, falling back to the default constant.
    final var sign = new TokenSignImpl(testKey(), null);
    assertThat(sign.getAlgorithm()).isEqualTo(TokenSignImpl.ENCRYPTION_ALGORITHM_DEFAULT);
  }

  @Test
  public void shouldRejectUnknownAlgorithm() {
    // The constructor probes Mac.getInstance(algorithm); unknown algorithms surface as
    // IllegalArgumentException.
    assertThatThrownBy(() -> new TokenSignImpl(testKey(), "FAKE-MAC-DOES-NOT-EXIST"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Can't find encryption algorithm");
  }

  @Test
  public void shouldConstructFromContextConfiguration() {
    // The single-arg constructor reads the secret-key + algorithm from ContextConfiguration.
    // We provide an empty config; readKeyFromConfig falls through to the SecureRandom branch
    // and produces a usable random key. Signing must still work end-to-end.
    final var ctx = new ContextConfiguration();
    final var sign = new TokenSignImpl(ctx);
    assertThat(sign.getAlgorithm())
        .isEqualTo(
            GlobalConfiguration.NETWORK_TOKEN_ENCRYPTION_ALGORITHM.getValueAsString());
    assertThat(sign.getDefaultKey()).isEqualTo("default");

    final var header = stubHeader();
    final var payload = "ctx-payload".getBytes();
    final var sig = sign.signToken(header, payload);
    assertThat(sign.verifyTokenSign(new ParsedToken(stubToken(header), payload, sig))).isTrue();
  }

  /**
   * Latent-bug pin for {@link TokenSignImpl#readKeyFromConfig}. Production source
   * (lines 130–143) reads:
   *
   * <pre>
   * if (configKey == null || configKey.length() == 0) {
   *   if (configKey != null &amp;&amp; configKey.length() &gt; 0) {  // UNREACHABLE — negation of outer
   *     key = Base64.getUrlDecoder().decode(configKey);
   *   }
   * }
   * </pre>
   *
   * <p>The inner condition is the logical negation of the outer condition, so the
   * Base64-decode branch is never reached even when {@code NETWORK_TOKEN_SECRETKEY}
   * is configured to a non-null non-empty value. As a result, every
   * {@link TokenSignImpl} instance falls through to the {@link
   * java.security.SecureRandom}-derived key path. The observable consequence:
   * <strong>two TokenSignImpl instances configured with the same
   * {@code NETWORK_TOKEN_SECRETKEY} produce mutually-unverifiable signatures.</strong>
   * Tokens cannot be verified across server restarts or across servers in a cluster
   * regardless of operator configuration.
   *
   * <p>WHEN-FIXED: YTDB-723 — fix the readKeyFromConfig nesting so the configured
   * secret key is honoured (e.g., flatten to a single conditional that decodes when
   * {@code configKey} is non-null and non-empty). Once fixed, this test’s
   * cross-instance verification must succeed; flip the .isFalse() assertion below
   * to .isTrue().
   */
  @Test
  public void readKeyFromConfigIgnoresConfiguredSecretKeyLatentBugPin() {
    // Build two ContextConfigurations sharing the SAME NETWORK_TOKEN_SECRETKEY.
    // A correct implementation would decode the configured key and produce two
    // TokenSignImpl instances with identical underlying HMAC keys; the current
    // implementation discards the configured value and seeds each instance with
    // its own SecureRandom-derived key.
    final var sharedSecret = Base64.getUrlEncoder().encodeToString(testKey());

    final var ctxA = new ContextConfiguration();
    ctxA.setValue(GlobalConfiguration.NETWORK_TOKEN_SECRETKEY, sharedSecret);
    final var ctxB = new ContextConfiguration();
    ctxB.setValue(GlobalConfiguration.NETWORK_TOKEN_SECRETKEY, sharedSecret);

    final var signA = new TokenSignImpl(ctxA);
    final var signB = new TokenSignImpl(ctxB);

    final var header = stubHeader();
    final var payload = "cross-instance-payload".getBytes();

    final var signatureFromA = signA.signToken(header, payload);
    final var crossVerified = signB.verifyTokenSign(
        new ParsedToken(stubToken(header), payload, signatureFromA));

    // WHEN-FIXED: YTDB-723 — once readKeyFromConfig honours the configured
    // secret key, this assertion must flip from .isFalse() to .isTrue() because
    // signA and signB will share the same underlying HMAC key.
    assertThat(crossVerified).isFalse();
  }
}
