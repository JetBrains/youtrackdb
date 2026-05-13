package com.jetbrains.youtrackdb.internal.core.security.authenticator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.AuthenticationInfo;
import com.jetbrains.youtrackdb.internal.core.metadata.security.auth.UserPasswordAuthInfo;
import com.jetbrains.youtrackdb.internal.core.security.SecurityAuthenticator;
import com.jetbrains.youtrackdb.internal.core.security.SecurityComponent;
import java.util.Map;
import org.junit.Test;

/**
 * Tests the concrete behaviour implemented by {@link SecurityAuthenticatorAbstract} —
 * the base class for all three default authenticators
 * ({@link DefaultPasswordAuthenticator}, {@link ServerConfigAuthenticator},
 * {@link SystemUserAuthenticator}).
 *
 * <p>Uses a minimal concrete subclass that exposes the config-driven state for assertion;
 * the subclass introduces no new logic — every call delegates straight to the
 * base-class implementation.
 */
public class SecurityAuthenticatorAbstractTest {

  /** Minimal concrete subclass; no extra logic — all calls go straight to the base. */
  private static class StubAuthenticator extends SecurityAuthenticatorAbstract {

    @Override
    public com.jetbrains.youtrackdb.internal.core.security.SecurityUser authenticate(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
        String username,
        String password) {
      // Delegates to base-class default: returns null (fall-through in the chain).
      return null;
    }
  }

  @Test
  public void defaultEnabledStateShouldBeTrue() {
    // A freshly constructed authenticator is enabled before config() is called.
    var auth = new StubAuthenticator();
    assertThat(auth.isEnabled()).isTrue();
  }

  @Test
  public void configNullMapShouldLeaveDefaultsIntact() {
    // Passing null for the JSON config map must not throw; defaults remain.
    var auth = new StubAuthenticator();
    auth.config(null, null, null);
    assertThat(auth.isEnabled()).isTrue();
    assertThat(auth.getName()).isEmpty();
  }

  @Test
  public void configShouldApplyEnabledFlag() {
    // Passing enabled=false in the config map must flip isEnabled().
    var auth = new StubAuthenticator();
    auth.config(null, Map.of("enabled", false), null);
    assertThat(auth.isEnabled()).isFalse();
  }

  @Test
  public void configShouldApplyNameField() {
    // A "name" entry in the config map must be reflected by getName().
    var auth = new StubAuthenticator();
    auth.config(null, Map.of("name", "test-auth"), null);
    assertThat(auth.getName()).isEqualTo("test-auth");
  }

  @Test
  public void configShouldAcceptDebugAndCaseSensitiveFlags() {
    // The "debug" and "caseSensitive" keys must be accepted without throwing.
    // The visible effect of caseSensitive=false surfaces in DefaultPasswordAuthenticator
    // (covered in DefaultPasswordAuthenticatorTest). Here we only pin that parsing
    // the flags does not throw — a future implementation that rejects unknown
    // keys would surface here.
    var auth = new StubAuthenticator();
    assertThatNoException().isThrownBy(
        () -> auth.config(null, Map.of("debug", true, "caseSensitive", false), null));
  }

  @Test
  public void activeAndDisposeShouldBeNoOps() {
    // The base implementation has empty bodies — must not throw. The falsifiable
    // signal is "no exception"; a regression that adds throwing logic would fail
    // here.
    var auth = new StubAuthenticator();
    assertThatNoException().isThrownBy(() -> {
      auth.active();
      auth.dispose();
    });
  }

  @Test
  public void getAuthenticationHeaderWithDatabaseNameShouldContainDbPrefix() {
    // The default header follows the "WWW-Authenticate: Basic realm="YouTrackDB db-X"" pattern.
    var auth = new StubAuthenticator();
    var header = auth.getAuthenticationHeader("mydb");
    assertThat(header).contains("Basic realm=").contains("mydb");
  }

  @Test
  public void getAuthenticationHeaderWithNullDatabaseShouldContainServerRealm() {
    // When databaseName is null the header uses the server-realm variant.
    var auth = new StubAuthenticator();
    var header = auth.getAuthenticationHeader(null);
    assertThat(header).contains("Server");
  }

  @Test
  public void getClientSubjectShouldReturnNull() {
    // Default SSO Subject is null — there is no JAAS subject for username/password auth.
    var auth = new StubAuthenticator();
    assertThat(auth.getClientSubject()).isNull();
  }

  @Test
  public void isSingleSignOnSupportedShouldReturnFalse() {
    // The base class does not support SSO; concrete implementations override when needed.
    var auth = new StubAuthenticator();
    assertThat(auth.isSingleSignOnSupported()).isFalse();
  }

  @Test
  public void getUserShouldReturnNull() {
    // The base implementation returns null — it has no user store of its own.
    var auth = new StubAuthenticator();
    assertThat(auth.getUser("anyone", null)).isNull();
  }

  @Test
  public void isAuthorizedShouldReturnFalse() {
    // The base implementation denies everything — subclasses override.
    var auth = new StubAuthenticator();
    assertThat(auth.isAuthorized(null, "anyone", "database.*")).isFalse();
  }

  @Test
  public void authenticateWithAuthInfoShouldReturnNull() {
    // The base AuthenticationInfo overload returns null so the chain can fall through.
    var auth = new StubAuthenticator();
    AuthenticationInfo info = new UserPasswordAuthInfo();
    assertThat(auth.authenticate(null, info)).isNull();
  }

  @Test
  public void implementsSecurityAuthenticatorAndSecurityComponent() {
    // Shape pin: the abstract class implements both parent interfaces so
    // DefaultSecuritySystem can treat it as SecurityComponent for lifecycle calls
    // and SecurityAuthenticator for auth chain dispatch.
    assertThat(
        SecurityAuthenticator.class.isAssignableFrom(SecurityAuthenticatorAbstract.class))
        .isTrue();
    assertThat(
        SecurityComponent.class.isAssignableFrom(SecurityAuthenticatorAbstract.class))
        .isTrue();
  }
}
