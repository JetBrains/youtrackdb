package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import org.junit.Test;

/**
 * Tests {@link ParsedToken} as a value-class triple. The constructor stores the three
 * arguments by reference; the getters return the same references back. There is no
 * defensive copy in the production code, so reference identity is the contract — pinning
 * that here prevents an accidental copy from breaking downstream consumers that compare
 * by reference.
 */
public class ParsedTokenTest {

  private static Token stubToken() {
    return new Token() {
      @Override
      public TokenHeader getHeader() {
        return null;
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
      public com.jetbrains.youtrackdb.internal.core.security.SecurityUser
          getUser(DatabaseSessionEmbedded session) {
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
        return 0;
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

  @Test
  public void shouldExposeConstructorArgumentsByReference() {
    final var token = stubToken();
    final var bytes = new byte[] {1, 2, 3};
    final var sig = new byte[] {4, 5, 6};

    final var parsed = new ParsedToken(token, bytes, sig);

    assertThat(parsed.getToken()).isSameAs(token);
    assertThat(parsed.getTokenBytes()).isSameAs(bytes);
    assertThat(parsed.getSignature()).isSameAs(sig);
  }

  @Test
  public void shouldAllowNullArguments() {
    // The production constructor performs no null-check; pinning that null is acceptable
    // because TokenSignImpl is allowed to accept ParsedToken with stale fields and will
    // surface the issue when verifyTokenSign tries to use them.
    final var parsed = new ParsedToken(null, null, null);
    assertThat(parsed.getToken()).isNull();
    assertThat(parsed.getTokenBytes()).isNull();
    assertThat(parsed.getSignature()).isNull();
  }
}
