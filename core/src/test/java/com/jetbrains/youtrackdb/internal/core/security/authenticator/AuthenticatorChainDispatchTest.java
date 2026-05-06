package com.jetbrains.youtrackdb.internal.core.security.authenticator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityAccessException;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Token;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.TokenAuthInfo;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.UserPasswordAuthInfo;
import com.jetbrains.youtrackdb.internal.core.metadata.security.jwt.TokenHeader;
import com.jetbrains.youtrackdb.internal.core.security.DefaultSecuritySystem;
import com.jetbrains.youtrackdb.internal.core.security.ParsedToken;
import com.jetbrains.youtrackdb.internal.core.security.SecurityUser;
import org.junit.After;
import org.junit.Test;

/**
 * Integration-level tests for the default 3-entry authenticator chain installed by
 * {@link DefaultSecuritySystem} when no external security.json is present:
 * {@code ServerConfigAuthenticator → SystemUserAuthenticator → DatabaseUserAuthenticator}.
 *
 * <p>Tests drive the chain via
 * {@link DefaultSecuritySystem#authenticate(DatabaseSessionEmbedded, String, String)}
 * and
 * {@link DefaultSecuritySystem#authenticate(DatabaseSessionEmbedded,
 *     com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo)}.
 * {@link DbTestBase} supplies a live in-memory database so the
 * {@link DatabaseUserAuthenticator} leg can look up users in the security schema.
 *
 * <p>Token stubs use the anonymous pattern from {@code TokenSignImplTest}:
 * {@code BinaryToken} and {@code JsonWebToken} are not referenced here.
 */
public class AuthenticatorChainDispatchTest extends DbTestBase {

  /**
   * Roll back any transaction left open by a failing test before
   * {@link DbTestBase#afterTest()} drops the database.  JUnit 4 runs subclass
   * {@code @After} methods before superclass ones, so this safety net fires ahead of
   * the database teardown.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  /**
   * Returns the {@link DefaultSecuritySystem} from the current test session's shared context.
   */
  private DefaultSecuritySystem securitySystem() {
    return session.getSharedContext().getYouTrackDB().getSecuritySystem();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Chain-level: username + password dispatch
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void chainShouldAuthenticateDbAdminUserViaPasswordLeg() {
    // The DatabaseUserAuthenticator leg (third in the chain) handles database users.
    // DbTestBase creates the database with admin/adminpwd; the chain must reach that
    // leg and return a non-null SecurityUser.
    var user = securitySystem().authenticate(session, adminUser, adminPassword);
    assertThat(user).isNotNull();
    assertThat(user.getName(session)).isEqualTo(adminUser);
  }

  @Test
  public void chainShouldReturnNullForUnknownUser() {
    // No leg recognises an unknown username; the chain exhausts all entries and returns
    // null rather than throwing.
    var user = securitySystem().authenticate(session, "ghost_user_xyz", "any_password");
    assertThat(user).isNull();
  }

  @Test
  public void chainShouldReturnNullOnWrongPasswordForExistingDbUser() {
    // DatabaseUserAuthenticator throws SecurityAccessException when the user exists but
    // the password is wrong. DefaultSecuritySystem's authenticate() wraps the loop in a
    // try/catch and returns null on exception — pinning that the chain does not propagate
    // the exception to the caller.
    var user = securitySystem().authenticate(session, adminUser, "definitely_wrong_password");
    assertThat(user).isNull();
  }

  @Test
  public void enabledAuthenticatorsListShouldContainThreeEntries() {
    // initDefultAuthenticators installs exactly three entries in the order:
    // ServerConfigAuthenticator, SystemUserAuthenticator, DatabaseUserAuthenticator.
    var list = securitySystem().getAuthenticatorsList();
    assertThat(list).hasSize(3);
    var types = list.stream().map(a -> a.getClass().getSimpleName()).toList();
    assertThat(types).containsExactly(
        "ServerConfigAuthenticator",
        "SystemUserAuthenticator",
        "DatabaseUserAuthenticator");
  }

  @Test
  public void getPrimaryAuthenticatorReturnsNullWhenSecurityDisabled() {
    // getPrimaryAuthenticator() is guarded by the "enabled" flag, which is false in the
    // default embedded context (no security.json loaded). It returns null in that case
    // rather than the first chain entry.
    var primary = securitySystem().getPrimaryAuthenticator();
    assertThat(primary).isNull();
  }

  @Test
  public void getAuthenticatorByNullShouldReturnFirst() {
    // getAuthenticator(null) returns the first registered authenticator.
    var auth = securitySystem().getAuthenticator(null);
    assertThat(auth).isInstanceOf(ServerConfigAuthenticator.class);
  }

  @Test
  public void getAuthenticatorByEmptyStringShouldReturnFirst() {
    // getAuthenticator("") also returns the first entry — same guard as null.
    var auth = securitySystem().getAuthenticator("");
    assertThat(auth).isInstanceOf(ServerConfigAuthenticator.class);
  }

  @Test
  public void getAuthenticatorByUnknownNameShouldReturnNull() {
    // An unregistered name produces null rather than throwing.
    assertThat(securitySystem().getAuthenticator("no-such-method")).isNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Chain-level: AuthenticationInfo overload
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void chainShouldAuthenticateViaUserPasswordAuthInfo() {
    // The UserPasswordAuthInfo overload is dispatched by DatabaseUserAuthenticator's
    // authenticate(session, AuthenticationInfo) branch which delegates to the
    // username/password overload.  The admin user must be found and returned.
    var authInfo = buildUserPasswordAuthInfo(adminUser, adminPassword);
    var user = securitySystem().authenticate(session, authInfo);
    assertThat(user).isNotNull();
    assertThat(user.getName(session)).isEqualTo(adminUser);
  }

  @Test
  public void chainShouldReturnNullForUnknownUserViaAuthInfo() {
    // Unknown user via the AuthenticationInfo path also results in null from the chain.
    var authInfo = buildUserPasswordAuthInfo("nobody_xyz", "pw");
    var user = securitySystem().authenticate(session, authInfo);
    assertThat(user).isNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // DatabaseUserAuthenticator — token path
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void databaseUserAuthenticatorShouldSucceedWithValidSignedToken() {
    // Build a ParsedToken with a valid HMAC signature produced by the system's TokenSign.
    // A token that is signed correctly and has isValid=true must be accepted, and the
    // admin user is returned via the userName-based lookup.
    var tokenSign = securitySystem().getTokenSign();
    var header = stubHeader();
    var payload = "test-payload-bytes".getBytes();
    var sig = tokenSign.signToken(header, payload);

    var token = stubToken(header, /*isValid=*/true, adminUser, session.getDatabaseName());
    var parsed = new ParsedToken(token, payload, sig);
    var authInfo = new TokenAuthInfo(parsed);

    var dba = new DatabaseUserAuthenticator();
    dba.config(session, null, securitySystem());

    var user = dba.authenticate(session, authInfo);
    assertThat(user).isNotNull();
    assertThat(user.getName(session)).isEqualTo(adminUser);
  }

  @Test
  public void databaseUserAuthenticatorShouldThrowOnTamperedToken() {
    // Flipping a bit in the signature causes verifyTokenSign() to return false, which
    // DatabaseUserAuthenticator translates to SecurityAccessException("expired").
    var tokenSign = securitySystem().getTokenSign();
    var header = stubHeader();
    var payload = "another-payload".getBytes();
    var sig = tokenSign.signToken(header, payload);
    sig[0] ^= (byte) 0xFF; // flip every bit of the first byte

    var token = stubToken(header, /*isValid=*/true, adminUser, session.getDatabaseName());
    var parsed = new ParsedToken(token, payload, sig);
    var authInfo = new TokenAuthInfo(parsed);

    var dba = new DatabaseUserAuthenticator();
    dba.config(session, null, securitySystem());

    assertThatThrownBy(() -> dba.authenticate(session, authInfo))
        .isInstanceOf(SecurityAccessException.class)
        .hasMessageContaining("expired");
  }

  @Test
  public void databaseUserAuthenticatorShouldThrowWhenTokenNotValid() {
    // A token whose isValid flag is false (even when the signature is correct) is rejected
    // with SecurityAccessException("Token not valid").
    var tokenSign = securitySystem().getTokenSign();
    var header = stubHeader();
    var payload = "payload".getBytes();
    var sig = tokenSign.signToken(header, payload);

    var token = stubToken(header, /*isValid=*/false, adminUser, session.getDatabaseName());
    var parsed = new ParsedToken(token, payload, sig);
    var authInfo = new TokenAuthInfo(parsed);

    var dba = new DatabaseUserAuthenticator();
    dba.config(session, null, securitySystem());

    assertThatThrownBy(() -> dba.authenticate(session, authInfo))
        .isInstanceOf(SecurityAccessException.class)
        .hasMessageContaining("not valid");
  }

  @Test
  public void databaseUserAuthenticatorShouldReturnNullWithNullSession() {
    // Without a session the DatabaseUserAuthenticator can't query the DB; it returns
    // null immediately for the username/password path.
    var dba = new DatabaseUserAuthenticator();
    dba.config(session, null, securitySystem());

    var user = dba.authenticate(null, adminUser, adminPassword);
    assertThat(user).isNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // ServerConfigAuthenticator — shape pins
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void configAuthenticatorExtendsSecurityAuthenticatorAbstract() {
    // Shape pin: ServerConfigAuthenticator must extend SecurityAuthenticatorAbstract so
    // DefaultSecuritySystem can call its lifecycle and authentication methods.
    assertThat(SecurityAuthenticatorAbstract.class.isAssignableFrom(
        ServerConfigAuthenticator.class)).isTrue();
  }

  @Test
  public void configAuthenticatorGetUserReturnsNullInEmbeddedContext() {
    // In the embedded test context there are no server-config users; getUser must
    // return null rather than throw.
    var sca = new ServerConfigAuthenticator();
    sca.config(session, null, securitySystem());

    assertThat(sca.getUser("ADMIN", session)).isNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SystemUserAuthenticator — shape pins
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void systemUserAuthenticatorShouldReturnNullWhenNoSystemUser() {
    // In the embedded test context there are no system users; authenticate() must
    // return null rather than throw.
    var sua = new SystemUserAuthenticator();
    sua.config(session, null, securitySystem());

    var user = sua.authenticate(session, "no_such_system_user", "pw");
    assertThat(user).isNull();
  }

  @Test
  public void systemUserAuthenticatorIsAuthorizedWithNullArgsShouldReturnFalse() {
    // The null-guard in isAuthorized() short-circuits immediately with false;
    // both branches must be reachable without throwing.
    var sua = new SystemUserAuthenticator();
    sua.config(session, null, securitySystem());

    assertThat(sua.isAuthorized(session, null, "resource")).isFalse();
    assertThat(sua.isAuthorized(session, "user", null)).isFalse();
  }

  @Test
  public void systemUserAuthenticatorGetUserReturnsNullInEmbeddedContext() {
    // getUser() delegates to getSystemUser(); in the embedded context this is null.
    var sua = new SystemUserAuthenticator();
    sua.config(session, null, securitySystem());

    assertThat(sua.getUser("ghost", session)).isNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers — anonymous token stubs
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Minimal anonymous Token stub.  Only {@code getHeader()}, {@code getIsValid()},
   * {@code getUserName()}, and {@code getDatabaseName()} are exercised by
   * {@link DatabaseUserAuthenticator}; all other methods return safe defaults.
   * Neither {@code BinaryToken} nor {@code JsonWebToken} is referenced.
   */
  private static Token stubToken(
      TokenHeader header, boolean isValid, String userName, String dbName) {
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
      public void setIsVerified(boolean v) {
      }

      @Override
      public boolean getIsValid() {
        return isValid;
      }

      @Override
      public void setIsValid(boolean v) {
      }

      @Override
      public String getUserName() {
        return userName;
      }

      @Override
      public SecurityUser getUser(DatabaseSessionEmbedded s) {
        return null;
      }

      @Override
      public String getDatabaseName() {
        return dbName;
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
        return Long.MAX_VALUE;
      }

      @Override
      public void setExpiry(long e) {
      }

      @Override
      public boolean isNowValid() {
        return isValid;
      }

      @Override
      public boolean isCloseToExpire() {
        return false;
      }
    };
  }

  /**
   * Minimal anonymous TokenHeader stub; {@link DefaultKeyProvider} does not inspect
   * any header field, so all accessors may back bare local variables.
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
      public void setAlgorithm(String a) {
        alg = a;
      }

      @Override
      public String getType() {
        return typ;
      }

      @Override
      public void setType(String t) {
        typ = t;
      }

      @Override
      public String getKeyId() {
        return kid;
      }

      @Override
      public void setKeyId(String k) {
        kid = k;
      }
    };
  }

  /**
   * Builds a {@link UserPasswordAuthInfo} with user + password set via reflection.
   * The class has no public constructor that accepts both fields; the server's HTTP
   * authentication layer populates them the same way.
   */
  private static UserPasswordAuthInfo buildUserPasswordAuthInfo(String user, String password) {
    try {
      var info = new UserPasswordAuthInfo();
      var userField = UserPasswordAuthInfo.class.getDeclaredField("user");
      userField.setAccessible(true);
      userField.set(info, user);
      var pwField = UserPasswordAuthInfo.class.getDeclaredField("password");
      pwField.setAccessible(true);
      pwField.set(info, password);
      return info;
    } catch (Exception e) {
      throw new RuntimeException("Failed to build UserPasswordAuthInfo via reflection", e);
    }
  }
}
