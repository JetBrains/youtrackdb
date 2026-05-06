package com.jetbrains.youtrackdb.internal.core.metadata.security.auth;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import com.jetbrains.youtrackdb.internal.core.security.ParsedToken;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the three thin value classes in {@code core/metadata/security/auth}:
 * {@link AuthenticationInfo} (interface shape), {@link TokenAuthInfo}, and
 * {@link UserPasswordAuthInfo}.
 *
 * <p>All tests are standalone — no database is needed because these classes hold plain fields.
 */
public class AuthInfoTest {

  // ─── AuthenticationInfo interface shape ─────────────────────────────────

  /**
   * Verifies that {@link AuthenticationInfo} is an interface with a single
   * {@code getDatabase()} method (shape pin for future callers).
   */
  @Test
  public void testAuthenticationInfoIsInterface() {
    Assert.assertTrue("AuthenticationInfo must be an interface",
        AuthenticationInfo.class.isInterface());
    try {
      var method = AuthenticationInfo.class.getDeclaredMethod("getDatabase");
      Assert.assertEquals("getDatabase return type must be Optional",
          java.util.Optional.class, method.getReturnType());
    } catch (NoSuchMethodException e) {
      Assert.fail("AuthenticationInfo must declare getDatabase(): " + e);
    }
  }

  // ─── UserPasswordAuthInfo ────────────────────────────────────────────────

  /**
   * Verifies that a freshly constructed {@link UserPasswordAuthInfo} (no-arg constructor) has
   * null fields and returns an empty Optional from getDatabase().
   */
  @Test
  public void testUserPasswordAuthInfoDefaultConstructorNullFields() {
    var info = new UserPasswordAuthInfo();

    // All fields are null by default (no setter was called)
    Assert.assertFalse("getDatabase() must be empty when database field is null",
        info.getDatabase().isPresent());
    Assert.assertNull("getUser() must be null by default", info.getUser());
    Assert.assertNull("getPassword() must be null by default", info.getPassword());
  }

  /**
   * Verifies that {@link UserPasswordAuthInfo} implements {@link AuthenticationInfo}.
   */
  @Test
  public void testUserPasswordAuthInfoImplementsAuthenticationInfo() {
    Assert.assertTrue(
        AuthenticationInfo.class.isAssignableFrom(UserPasswordAuthInfo.class));
  }

  // ─── TokenAuthInfo ──────────────────────────────────────────────────────

  /**
   * Verifies that {@link TokenAuthInfo} stores the supplied {@link ParsedToken} and delegates
   * {@code getDatabase()} to the token's underlying {@code getDatabaseName()} value.
   */
  @Test
  public void testTokenAuthInfoGetDatabaseDelegatestoTokenDatabaseName() {
    // Build a minimal Token stub whose getDatabaseName() returns "testDb"
    var stubToken = buildTokenStub("testDb");
    var parsedToken = new ParsedToken(stubToken, new byte[0], new byte[0]);

    var info = new TokenAuthInfo(parsedToken);

    Assert.assertSame("getToken() must return the ParsedToken passed to the constructor",
        parsedToken, info.getToken());
    Assert.assertTrue("getDatabase() must be present when token has a database name",
        info.getDatabase().isPresent());
    Assert.assertEquals("testDb", info.getDatabase().get());
  }

  /**
   * Verifies that {@link TokenAuthInfo#getDatabase()} returns an empty Optional when the
   * token's {@code getDatabaseName()} returns null.
   */
  @Test
  public void testTokenAuthInfoGetDatabaseEmptyWhenTokenDatabaseNull() {
    var stubToken = buildTokenStub(null);
    var parsedToken = new ParsedToken(stubToken, new byte[0], new byte[0]);

    var info = new TokenAuthInfo(parsedToken);

    Assert.assertFalse("getDatabase() must be empty when token.getDatabaseName() is null",
        info.getDatabase().isPresent());
  }

  /**
   * Verifies that {@link TokenAuthInfo} implements {@link AuthenticationInfo}.
   */
  @Test
  public void testTokenAuthInfoImplementsAuthenticationInfo() {
    Assert.assertTrue(
        AuthenticationInfo.class.isAssignableFrom(TokenAuthInfo.class));
  }

  // ─── helpers ─────────────────────────────────────────────────────────────

  /**
   * Builds a minimal {@link Token} stub that returns the given {@code databaseName}
   * from {@code getDatabaseName()}.  All other methods may return default / no-op values.
   */
  private static Token buildTokenStub(final String databaseName) {
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
        return true;
      }

      @Override
      public void setIsValid(boolean valid) {
      }

      @Override
      public String getUserName() {
        return null;
      }

      @Override
      public SecurityUser getUser(DatabaseSessionEmbedded session) {
        return null;
      }

      @Override
      public String getDatabaseName() {
        return databaseName;
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
        return true;
      }

      @Override
      public boolean isCloseToExpire() {
        return false;
      }
    };
  }
}
