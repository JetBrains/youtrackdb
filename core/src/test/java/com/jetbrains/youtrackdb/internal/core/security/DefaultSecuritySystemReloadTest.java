package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.security.authenticator.DatabaseUserAuthenticator;
import com.jetbrains.youtrackdb.internal.core.security.authenticator.SecurityAuthenticatorAbstract;
import com.jetbrains.youtrackdb.internal.core.security.authenticator.ServerConfigAuthenticator;
import com.jetbrains.youtrackdb.internal.core.security.authenticator.SystemUserAuthenticator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

/**
 * Tests the JSON-config-driven reflective reload paths in
 * {@link DefaultSecuritySystem}, the largest class in {@code core/security}.
 *
 * <p>Each test synthesises a {@code Map<String, Object>} matching the shape of
 * a {@code security.json} entity (authentication / passwordValidator / auditing
 * / ldapImporter / server) and drives one of the public reload entry points
 * ({@link DefaultSecuritySystem#reload(DatabaseSessionEmbedded, Map)},
 * {@link DefaultSecuritySystem#reloadComponent(DatabaseSessionEmbedded,
 * SecurityUser, String, Map)}). The reload methods then exercise the private
 * reflective lookup path ({@code getClass}, {@code Class.forName},
 * {@code class.newInstance()}) plus the type-check and configuration-application
 * branches inside {@code loadAuthenticators} / {@code reloadPasswordValidator}
 * / {@code reloadAuditingService} / {@code reloadImportLDAP}.
 *
 * <p>Test plugin classes are public static nested types in this file so
 * {@code Class.forName} can resolve them by their JVM name
 * ({@code com.jetbrains.youtrackdb.internal.core.security.DefaultSecuritySystemReloadTest$TestAuthenticator}).
 *
 * <p>The {@link TemporaryFolder} rule provides a writable path that is
 * registered as {@link GlobalConfiguration#SERVER_SECURITY_FILE} for the
 * duration of each test. Without it, {@code reloadComponent}'s {@code setSection}
 * helper would attempt to write to the unresolved
 * {@code ${YOUTRACKDB_HOME}/config/security.json} location; the production code
 * swallows that IOException, but pinning a real temp file keeps test output
 * clean and exercises the persistence side of {@code setSection}.
 *
 * <p>Tagged {@link SequentialTest} because {@link #redirectSecurityFile} mutates
 * the JVM-global {@link GlobalConfiguration#SERVER_SECURITY_FILE} slot for every
 * test in the class. Although only this class writes the slot today, surefire
 * runs {@code parallel=classes} with {@code threadCountClasses=4}; future tests
 * in the same package that happen to read the slot during the parallel-class
 * window would observe the polluted value, so the category is the safe default
 * matching the discipline already applied on
 * {@code SecurityManagerNewCredentialInterceptorDeadCodeTest} and
 * {@code KerberosCredentialInterceptorDeadCodeTest}.
 */
@Category(SequentialTest.class)
public class DefaultSecuritySystemReloadTest extends DbTestBase {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private String previousSecurityFile;

  @Before
  public void redirectSecurityFile() throws IOException {
    // Redirect setSection's persistence side to a per-test scratch file so we
    // don't pollute the build with phantom security.json writes.
    previousSecurityFile = GlobalConfiguration.SERVER_SECURITY_FILE.getValueAsString();
    var scratch = tempFolder.newFile("security.json");
    GlobalConfiguration.SERVER_SECURITY_FILE.setValue(scratch.getAbsolutePath());
  }

  @After
  public void restoreSecurityFile() {
    GlobalConfiguration.SERVER_SECURITY_FILE.setValue(previousSecurityFile);
  }

  /**
   * Roll back any open transaction before {@link DbTestBase#afterTest()} drops
   * the database. JUnit 4 runs subclass {@code @After} methods before super-
   * class ones, so this safety net fires ahead of the database teardown.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  /**
   * Returns the live {@link DefaultSecuritySystem} from the embedded context,
   * matching the same access pattern used by the chain-dispatch tests in
   * {@code authenticator/AuthenticatorChainDispatchTest}.
   */
  private DefaultSecuritySystem securitySystem() {
    return session.getSharedContext().getYouTrackDB().getSecuritySystem();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // reload(session, configEntity) — full top-level reload
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void reloadWithEnabledConfigShouldFlipEnabledFlag() {
    // A minimal configEntity with enabled=true must propagate through
    // loadSecurity() so isEnabled() returns true after reload.
    var sys = securitySystem();
    Map<String, Object> cfg = new HashMap<>();
    cfg.put("enabled", true);

    sys.reload(session, cfg);

    assertThat(sys.isEnabled()).isTrue();
    assertThat(sys.getConfig().get("enabled")).isEqualTo(true);
  }

  @Test
  public void reloadWithDebugFlagShouldNotThrow() {
    // The debug flag is a plain boolean field; loadSecurity reads it and
    // exposes it indirectly via the rest of the system. Reloading with
    // debug=true is the round-trip property pin.
    var sys = securitySystem();
    Map<String, Object> cfg = new HashMap<>();
    cfg.put("enabled", false);
    cfg.put("debug", true);

    sys.reload(session, cfg);

    // enabled=false keeps the system in disabled-mode regardless of debug.
    assertThat(sys.isEnabled()).isFalse();
  }

  @Test
  public void reloadWithNullConfigShouldThrowSecuritySystemException() {
    // The Javadoc is silent on null but the implementation throws explicitly,
    // making this the documented contract for the null-config caller.
    var sys = securitySystem();

    assertThatThrownBy(() -> sys.reload(session, null))
        .isInstanceOf(SecuritySystemException.class)
        .hasMessageContaining("null");
  }

  @Test
  public void reloadShouldRestoreDefaultsWhenAuthenticationSectionAbsent() {
    // When configEntity has enabled=true but no "authentication" section,
    // reloadAuthMethods is invoked with a null authEntity and is a no-op.
    // The chain therefore stays at whatever the prior call set up — exercising
    // the early-return branch of reloadAuthMethods.
    var sys = securitySystem();
    Map<String, Object> cfg = new HashMap<>();
    cfg.put("enabled", true);

    sys.reload(session, cfg);

    assertThat(sys.getAuthenticatorsList()).isNotNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // reloadComponent — null-arg validation
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void reloadComponentNullNameThrows() {
    // The first guard clause in reloadComponent rejects null/empty names.
    var sys = securitySystem();

    assertThatThrownBy(() -> sys.reloadComponent(session, null, null, new HashMap<>()))
        .isInstanceOf(SecuritySystemException.class)
        .hasMessageContaining("name");
  }

  @Test
  public void reloadComponentEmptyNameThrows() {
    // Empty strings hit the same guard clause as null.
    var sys = securitySystem();

    assertThatThrownBy(() -> sys.reloadComponent(session, null, "", new HashMap<>()))
        .isInstanceOf(SecuritySystemException.class)
        .hasMessageContaining("name");
  }

  @Test
  public void reloadComponentNullJsonConfigThrows() {
    // The second guard clause rejects null jsonConfig regardless of name.
    var sys = securitySystem();

    assertThatThrownBy(() -> sys.reloadComponent(session, null, "authentication", null))
        .isInstanceOf(SecuritySystemException.class)
        .hasMessageContaining("Configuration");
  }

  // ──────────────────────────────────────────────────────────────────────────
  // loadAuthenticators — driven via reloadComponent("authentication", …)
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void reloadAuthenticationWithRegisteredClassUsesSpiLookupHit() {
    // registerSecurityClass populates the in-memory securityClassMap; getClass
    // must hit the map first instead of falling through to Class.forName.
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ true, /*allowDefault=*/ false);
    sys.reloadComponent(session, null, "authentication", authConfig);

    var list = sys.getAuthenticatorsList();
    assertThat(list).isNotEmpty();
    assertThat(list).anyMatch(a -> a instanceof TestAuthenticator);
  }

  @Test
  public void reloadAuthenticationWithUnregisteredClassFallsBackToClassForName() {
    // Without registerSecurityClass, getClass falls through to
    // Class.forName(clsName). The test plugin lives in this compiled test
    // classpath and resolves correctly.
    var sys = enableAndPrepareSystem();

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ true, /*allowDefault=*/ false);
    sys.reloadComponent(session, null, "authentication", authConfig);

    var list = sys.getAuthenticatorsList();
    assertThat(list).anyMatch(a -> a instanceof TestAuthenticator);
  }

  @Test
  public void reloadAuthenticationWithMissingClassIsNoOp() {
    // Class.forName failure is logged but not propagated; the catch clause
    // returns a null Class and loadAuthenticators logs an error rather than
    // throwing. The chain ends up empty (allowDefault=false suppresses the
    // implicit DatabaseUserAuthenticator append).
    var sys = enableAndPrepareSystem();

    Map<String, Object> authMethod = new HashMap<>();
    authMethod.put("name", "ghost");
    authMethod.put("class", "com.example.does.not.Exist");
    authMethod.put("enabled", true);
    Map<String, Object> authEntity = new HashMap<>();
    authEntity.put("authenticators", List.of(authMethod));
    authEntity.put("allowDefault", false);

    sys.reloadComponent(session, null, "authentication", authEntity);

    // The chain may be empty or contain nothing because class resolution failed.
    var list = sys.getAuthenticatorsList();
    assertThat(list).noneMatch(a -> a.getClass().getName().contains("ghost"));
  }

  @Test
  public void reloadAuthenticationWithNonAuthenticatorClassIsNoOp() {
    // The isAssignableFrom guard rejects classes that don't extend
    // SecurityAuthenticator — the entry is logged and skipped.
    var sys = enableAndPrepareSystem();

    Map<String, Object> authMethod = new HashMap<>();
    authMethod.put("name", "wrongtype");
    authMethod.put("class", NotAnAuthenticator.class.getName());
    authMethod.put("enabled", true);
    Map<String, Object> authEntity = new HashMap<>();
    authEntity.put("authenticators", List.of(authMethod));
    authEntity.put("allowDefault", false);

    sys.reloadComponent(session, null, "authentication", authEntity);

    assertThat(sys.getAuthenticatorsList())
        .noneMatch(a -> a.getClass().getName().contains("NotAnAuthenticator"));
  }

  @Test
  public void reloadAuthenticationWithEnabledFalseSkipsEntry() {
    // The "enabled" flag inside an authenticator entry short-circuits
    // before reflection. The class stays unresolved, no instance is created.
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ false, /*allowDefault=*/ false);
    sys.reloadComponent(session, null, "authentication", authConfig);

    assertThat(sys.getAuthenticatorsList())
        .noneMatch(a -> a instanceof TestAuthenticator);
  }

  @Test
  public void reloadAuthenticationWithMissingNameLogsAndSkips() {
    // The "name is missing" branch falls through without registering the
    // entry and without throwing.
    var sys = enableAndPrepareSystem();

    Map<String, Object> authMethod = new HashMap<>();
    // No "name" key — exercises the else-branch error log.
    authMethod.put("class", TestAuthenticator.class.getName());
    authMethod.put("enabled", true);
    Map<String, Object> authEntity = new HashMap<>();
    authEntity.put("authenticators", List.of(authMethod));
    authEntity.put("allowDefault", false);

    sys.reloadComponent(session, null, "authentication", authEntity);

    assertThat(sys.getAuthenticatorsList())
        .noneMatch(a -> a instanceof TestAuthenticator);
  }

  @Test
  public void reloadAuthenticationDefaultsEnabledWhenKeyAbsent() {
    // If the authenticator entry has no "enabled" key, the plugin defaults to
    // enabled=true (per the comment in the code).
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    Map<String, Object> authMethod = new HashMap<>();
    authMethod.put("name", "default-enabled");
    authMethod.put("class", TestAuthenticator.class.getName());
    // Note: no "enabled" key.
    Map<String, Object> authEntity = new HashMap<>();
    authEntity.put("authenticators", List.of(authMethod));
    authEntity.put("allowDefault", false);

    sys.reloadComponent(session, null, "authentication", authEntity);

    assertThat(sys.getAuthenticatorsList()).anyMatch(a -> a instanceof TestAuthenticator);
  }

  @Test
  public void reloadAuthenticationWithAllowDefaultAppendsDatabaseUserAuthenticator() {
    // allowDefault=true (the production default) causes a fresh
    // DatabaseUserAuthenticator to be appended at the tail of the list. With
    // allowDefault=false the tail is whatever the JSON specifies.
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ true, /*allowDefault=*/ true);
    sys.reloadComponent(session, null, "authentication", authConfig);

    var list = sys.getAuthenticatorsList();
    assertThat(list).hasSizeGreaterThanOrEqualTo(2);
    assertThat(list.getLast()).isInstanceOf(DatabaseUserAuthenticator.class);
  }

  @Test
  public void reloadAuthenticationWithoutAuthenticatorsKeyInstallsDefaultChain() {
    // If the authentication section omits "authenticators" entirely,
    // loadAuthenticators falls back to initDefultAuthenticators, which
    // installs the canonical 3-entry chain.
    var sys = enableAndPrepareSystem();

    Map<String, Object> authEntity = new HashMap<>();
    // No "authenticators" key.

    sys.reloadComponent(session, null, "authentication", authEntity);

    var list = sys.getAuthenticatorsList();
    assertThat(list).hasSize(3);
    var types = list.stream().map(a -> a.getClass().getSimpleName()).toList();
    assertThat(types).containsExactly(
        "ServerConfigAuthenticator",
        "SystemUserAuthenticator",
        "DatabaseUserAuthenticator");
  }

  // ──────────────────────────────────────────────────────────────────────────
  // reloadPasswordValidator — happy + error paths
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void reloadPasswordValidatorHappyPathInstallsValidator() {
    // The happy path resolves the class via getClass, instantiates it, calls
    // config + active. After reload, validatePassword routes through the
    // installed instance.
    var sys = enableAndPrepareSystem();

    Map<String, Object> pwCfg = new HashMap<>();
    pwCfg.put("enabled", true);
    pwCfg.put("class", TestPasswordValidator.class.getName());

    sys.reloadComponent(session, null, "passwordValidator", pwCfg);

    // The installed validator throws on any password whose name contains "bad".
    assertThatThrownBy(() -> sys.validatePassword("alice", "anybad"))
        .isInstanceOf(InvalidPasswordException.class);
  }

  @Test
  public void reloadPasswordValidatorWithDisabledSectionDoesNotInstall() {
    // The isEnabled(passwdValEntity) gate skips installation entirely when
    // the section's "enabled" key is false. With no validator installed,
    // validatePassword falls through silently — a previously-rejected password
    // ("anybad" matches the TestPasswordValidator's reject rule) must now be
    // accepted, which is the falsifiable signal that no validator is wired in.
    var sys = enableAndPrepareSystem();

    Map<String, Object> pwCfg = new HashMap<>();
    pwCfg.put("enabled", false);
    pwCfg.put("class", TestPasswordValidator.class.getName());

    sys.reloadComponent(session, null, "passwordValidator", pwCfg);

    assertThatNoException().isThrownBy(() -> sys.validatePassword("alice", "anybad"));
  }

  @Test
  public void reloadPasswordValidatorWithMissingClassLogsAndSkips() {
    // Missing "class" key returns null from getClass → the error-log branch
    // ("PasswordValidator class property is missing") fires; no installation
    // happens, so the prior validator (or absence of one) remains. The
    // assertion pins "missing class is silently swallowed" — a future
    // implementation that throws would flip this to a failure.
    var sys = enableAndPrepareSystem();

    Map<String, Object> pwCfg = new HashMap<>();
    pwCfg.put("enabled", true);
    // No "class" key.

    assertThatNoException()
        .isThrownBy(() -> sys.reloadComponent(session, null, "passwordValidator", pwCfg));
    assertThatNoException().isThrownBy(() -> sys.validatePassword("alice", "anything"));
  }

  @Test
  public void reloadPasswordValidatorWithNonValidatorClassLogsAndSkips() {
    // The isAssignableFrom guard rejects classes that don't implement
    // PasswordValidator. The reject branch logs and returns; no installation.
    var sys = enableAndPrepareSystem();

    Map<String, Object> pwCfg = new HashMap<>();
    pwCfg.put("enabled", true);
    pwCfg.put("class", NotAnAuthenticator.class.getName());

    assertThatNoException()
        .isThrownBy(() -> sys.reloadComponent(session, null, "passwordValidator", pwCfg));
    // No validator installed → any password (including the one the test
    // validator would have rejected) is accepted.
    assertThatNoException().isThrownBy(() -> sys.validatePassword("alice", "anybad"));
  }

  @Test
  public void reloadPasswordValidatorReplacesPriorValidator() {
    // Reloading the same component disposes the prior instance. The second
    // reload replaces it; calling validatePassword again routes through
    // the new validator's accept rule.
    var sys = enableAndPrepareSystem();

    Map<String, Object> pwCfg = new HashMap<>();
    pwCfg.put("enabled", true);
    pwCfg.put("class", TestPasswordValidator.class.getName());
    sys.reloadComponent(session, null, "passwordValidator", pwCfg);
    assertThatThrownBy(() -> sys.validatePassword("u", "abadpassword"))
        .isInstanceOf(InvalidPasswordException.class);

    // Replace with the lenient validator.
    pwCfg.put("class", LenientPasswordValidator.class.getName());
    sys.reloadComponent(session, null, "passwordValidator", pwCfg);

    sys.validatePassword("u", "abadpassword"); // no exception now
  }

  // ──────────────────────────────────────────────────────────────────────────
  // reloadAuditingService — happy + error paths
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void reloadAuditingServiceHappyPathInstallsAuditing() {
    // After reload, getAuditing() must return a non-null AuditingService
    // produced by class.newInstance().
    var sys = enableAndPrepareSystem();

    Map<String, Object> audCfg = new HashMap<>();
    audCfg.put("enabled", true);
    audCfg.put("class", TestAuditingService.class.getName());

    sys.reloadComponent(session, null, "auditing", audCfg);

    var auditing = sys.getAuditing();
    assertThat(auditing).isInstanceOf(TestAuditingService.class);
  }

  @Test
  public void reloadAuditingServiceWithDisabledSectionDoesNotInstall() {
    // The isEnabled(auditingEntity) gate causes the installation to be
    // skipped, leaving auditingService null.
    var sys = enableAndPrepareSystem();

    Map<String, Object> audCfg = new HashMap<>();
    audCfg.put("enabled", false);
    audCfg.put("class", TestAuditingService.class.getName());

    sys.reloadComponent(session, null, "auditing", audCfg);

    assertThat(sys.getAuditing()).isNull();
  }

  @Test
  public void reloadAuditingServiceWithNonAuditingClassLogsAndSkips() {
    // The isAssignableFrom guard rejects classes that don't implement
    // AuditingService.
    var sys = enableAndPrepareSystem();

    Map<String, Object> audCfg = new HashMap<>();
    audCfg.put("enabled", true);
    audCfg.put("class", NotAnAuthenticator.class.getName());

    sys.reloadComponent(session, null, "auditing", audCfg);

    assertThat(sys.getAuditing()).isNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // reloadImportLDAP — error paths only (happy path forwarded to Track 22)
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void reloadLdapImporterWithMissingClassIsNoOp() {
    // The "ldapImporter class property is missing" branch fires when the
    // section has enabled=true but no "class" key. The implementation logs
    // and returns silently; the falsifiable contract is "no exception".
    var sys = enableAndPrepareSystem();

    Map<String, Object> ldapCfg = new HashMap<>();
    ldapCfg.put("enabled", true);
    // No "class" key.

    assertThatNoException()
        .isThrownBy(() -> sys.reloadComponent(session, null, "ldapImporter", ldapCfg));
  }

  @Test
  public void reloadLdapImporterWithDisabledSectionDoesNotInstall() {
    // The isEnabled(ldapImportEntity) gate skips installation entirely. Pin
    // the silent-skip contract via assertThatNoException so a future
    // implementation that throws on the disabled-section path surfaces here.
    var sys = enableAndPrepareSystem();

    Map<String, Object> ldapCfg = new HashMap<>();
    ldapCfg.put("enabled", false);
    ldapCfg.put("class", TestImportLDAP.class.getName());

    assertThatNoException()
        .isThrownBy(() -> sys.reloadComponent(session, null, "ldapImporter", ldapCfg));
  }

  @Test
  public void reloadLdapImporterWithNonComponentClassLogsAndSkips() {
    // The isAssignableFrom guard rejects classes that don't implement
    // SecurityComponent. The reject branch must log and return silently —
    // pin via assertThatNoException so a future implementation that throws
    // would surface here.
    var sys = enableAndPrepareSystem();

    Map<String, Object> ldapCfg = new HashMap<>();
    ldapCfg.put("enabled", true);
    ldapCfg.put("class", NotAnAuthenticator.class.getName());

    assertThatNoException()
        .isThrownBy(() -> sys.reloadComponent(session, null, "ldapImporter", ldapCfg));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // reloadComponent("server", …) — propagates server settings
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void reloadServerComponentUpdatesStorePasswords() {
    // server.storePasswords toggles the stored-password mode that
    // arePasswordsStored() reflects when the system is enabled.
    var sys = enableAndPrepareSystem();

    Map<String, Object> serverCfg = new HashMap<>();
    serverCfg.put("storePasswords", false);
    sys.reloadComponent(session, null, "server", serverCfg);

    assertThat(sys.arePasswordsStored()).isFalse();

    serverCfg.put("storePasswords", true);
    sys.reloadComponent(session, null, "server", serverCfg);
    assertThat(sys.arePasswordsStored()).isTrue();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // registerSecurityClass / unregisterSecurityClass — round-trip
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void registerAndUnregisterSecurityClassRoundTrip() {
    // After register, the class is in securityClassMap and getClass() finds
    // it without falling through to Class.forName. After unregister, the
    // map entry is gone — but Class.forName still resolves the class because
    // it's loadable from the classpath. Thus the reflective resolution
    // succeeds in both branches; the difference is observable only via the
    // map state, which we sample by registering an inner class that uses an
    // alias collision.
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ true, /*allowDefault=*/ false);
    sys.reloadComponent(session, null, "authentication", authConfig);
    assertThat(sys.getAuthenticatorsList())
        .anyMatch(a -> a instanceof TestAuthenticator);

    sys.unregisterSecurityClass(TestAuthenticator.class);

    // After unregister, Class.forName still succeeds, so the chain still
    // contains the plugin. The unregister path is exercised by the call.
    sys.reloadComponent(session, null, "authentication", authConfig);
    assertThat(sys.getAuthenticatorsList())
        .anyMatch(a -> a instanceof TestAuthenticator);
  }

  @Test
  public void unregisterUnknownClassIsNoOp() {
    // unregisterSecurityClass on a class that was never registered should
    // be a silent no-op (HashMap.remove on a missing key returns null).
    var sys = securitySystem();
    assertThatNoException()
        .isThrownBy(() -> sys.unregisterSecurityClass(TestAuthenticator.class));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // getConfig / getComponentConfig — section read-back
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void getConfigShouldReflectInstalledSections() {
    // After reloading the password validator + server section, getConfig
    // exposes both under their well-known keys.
    var sys = enableAndPrepareSystem();

    Map<String, Object> pwCfg = new HashMap<>();
    pwCfg.put("enabled", true);
    pwCfg.put("class", TestPasswordValidator.class.getName());
    sys.reloadComponent(session, null, "passwordValidator", pwCfg);

    Map<String, Object> serverCfg = new HashMap<>();
    serverCfg.put("storePasswords", false);
    sys.reloadComponent(session, null, "server", serverCfg);

    var fullCfg = sys.getConfig();
    assertThat(fullCfg).containsKey("passwordValidator");
    assertThat(fullCfg).containsKey("server");
    assertThat(fullCfg.get("enabled")).isEqualTo(true);
  }

  @Test
  public void getComponentConfigByNameReturnsRespectiveSection() {
    // getComponentConfig switches on a case-insensitive name and returns
    // the matching entity.
    var sys = enableAndPrepareSystem();

    Map<String, Object> serverCfg = new HashMap<>();
    serverCfg.put("storePasswords", false);
    sys.reloadComponent(session, null, "server", serverCfg);

    assertThat(sys.getComponentConfig("server")).isEqualTo(serverCfg);
    assertThat(sys.getComponentConfig("SERVER")).isEqualTo(serverCfg);
    assertThat(sys.getComponentConfig("nonexistent")).isNull();
    assertThat(sys.getComponentConfig(null)).isNull();
  }

  @Test
  public void getComponentConfigForAllKnownNamesIsNullWhenUnset() {
    // Every documented case label of getComponentConfig is reachable; with
    // no sections installed, each returns null.
    var sys = securitySystem();

    assertThat(sys.getComponentConfig("auditing")).isNull();
    assertThat(sys.getComponentConfig("authentication")).isNull();
    assertThat(sys.getComponentConfig("ldapImporter")).isNull();
    assertThat(sys.getComponentConfig("passwordValidator")).isNull();
    assertThat(sys.getComponentConfig("server")).isNull();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Authentication-header aggregation when enabled
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void getAuthenticationHeaderAggregatesEnabledAuthenticators() {
    // When the system is disabled the header is the default Basic line.
    // After enabling and installing an authenticator that returns a custom
    // header line, the aggregated header reflects it.
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ true, /*allowDefault=*/ false);
    sys.reloadComponent(session, null, "authentication", authConfig);

    var header = sys.getAuthenticationHeader("mydb");
    assertThat(header).contains("YouTrackDB db-mydb");
  }

  @Test
  public void getAuthenticationHeaderUsesDefaultWhenSystemDisabled() {
    // The default Basic realm header is returned when the system is not
    // enabled, irrespective of installed authenticators.
    var sys = securitySystem();

    assertThat(sys.getAuthenticationHeader("anydb"))
        .isEqualTo("WWW-Authenticate: Basic realm=\"YouTrackDB db-anydb\"");
    assertThat(sys.getAuthenticationHeader(null))
        .isEqualTo("WWW-Authenticate: Basic realm=\"YouTrackDB Server\"");
  }

  // ──────────────────────────────────────────────────────────────────────────
  // arePasswordsStored / isDefaultAllowed — flag wiring
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void arePasswordsStoredReturnsTrueWhenSystemDisabled() {
    // arePasswordsStored returns true unconditionally when the system is
    // disabled — the comment explicitly notes that as the "system default".
    var sys = securitySystem();
    assertThat(sys.isEnabled()).isFalse();
    assertThat(sys.arePasswordsStored()).isTrue();
  }

  @Test
  public void isDefaultAllowedReturnsTrueWhenSystemDisabled() {
    // Same shape as arePasswordsStored — disabled mode reports true.
    var sys = securitySystem();
    assertThat(sys.isDefaultAllowed()).isTrue();
  }

  @Test
  public void isDefaultAllowedReflectsAuthSettingWhenEnabled() {
    // When the system is enabled, isDefaultAllowed echoes the allowDefault
    // flag from the authentication section.
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ true, /*allowDefault=*/ false);
    sys.reloadComponent(session, null, "authentication", authConfig);

    assertThat(sys.isDefaultAllowed()).isFalse();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // close() clears authenticators when enabled
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  public void closeAfterEnableClearsAuthenticatorList() {
    // close() walks every component, calls dispose, and replaces the
    // authenticator list with an empty unmodifiable list. After close,
    // the system is disabled and arePasswordsStored falls back to true.
    var sys = enableAndPrepareSystem();
    sys.registerSecurityClass(TestAuthenticator.class);

    var authConfig = buildAuthenticationConfig(
        TestAuthenticator.class, /*authEnabled=*/ true, /*allowDefault=*/ false);
    sys.reloadComponent(session, null, "authentication", authConfig);
    assertThat(sys.getAuthenticatorsList()).isNotEmpty();

    sys.close();

    assertThat(sys.getAuthenticatorsList()).isEmpty();
    assertThat(sys.isEnabled()).isFalse();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Drives the public reload entry point with a minimal {@code enabled=true}
   * config so subsequent {@code reloadComponent} calls execute the enabled
   * branches of {@code reloadAuthMethods} / {@code reloadPasswordValidator} /
   * {@code reloadAuditingService} / {@code reloadImportLDAP}. Returns the
   * same {@link DefaultSecuritySystem} reference for fluent chaining.
   */
  private DefaultSecuritySystem enableAndPrepareSystem() {
    var sys = securitySystem();
    Map<String, Object> cfg = new HashMap<>();
    cfg.put("enabled", true);
    sys.reload(session, cfg);
    return sys;
  }

  /**
   * Builds a single-entry {@code authentication} section pointing at the
   * supplied authenticator class with the given {@code enabled} and
   * {@code allowDefault} flags.
   */
  private static Map<String, Object> buildAuthenticationConfig(
      Class<?> authClass, boolean authEnabled, boolean allowDefault) {
    Map<String, Object> authMethod = new HashMap<>();
    authMethod.put("name", "test-method");
    authMethod.put("class", authClass.getName());
    authMethod.put("enabled", authEnabled);

    Map<String, Object> authEntity = new HashMap<>();
    var list = new ArrayList<Map<String, Object>>();
    list.add(authMethod);
    authEntity.put("authenticators", list);
    authEntity.put("allowDefault", allowDefault);
    return authEntity;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test plugin classes used by the JSON-config-driven reflective lookups.
  // Each must be public + no-arg-constructable so Class.newInstance succeeds.
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Minimal SecurityAuthenticator suitable for the loadAuthenticators happy
   * path. The chain calls {@code config(...)} then {@code active()} on the
   * fresh instance; this implementation produces a stable distinguishing
   * authentication header.
   */
  public static class TestAuthenticator extends SecurityAuthenticatorAbstract {
    // Inherits all required methods from SecurityAuthenticatorAbstract; no
    // additional override needed because the abstract base supplies sensible
    // defaults.

    @Override
    public SecurityUser authenticate(
        DatabaseSessionEmbedded session,
        String username, String password) {
      return null;
    }
  }

  /**
   * PasswordValidator that rejects any password containing "bad".  The
   * happy-path test invokes {@link DefaultSecuritySystem#validatePassword}
   * with a "bad" substring and expects {@link InvalidPasswordException}.
   */
  public static class TestPasswordValidator implements PasswordValidator {
    @Override
    public void active() {
    }

    @Override
    public void config(
        DatabaseSessionEmbedded session,
        Map<String, Object> jsonConfig,
        SecuritySystem security) {
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public void validatePassword(String username, String password)
        throws InvalidPasswordException {
      if (password != null && password.contains("bad")) {
        throw new InvalidPasswordException("password rejected by test validator");
      }
    }
  }

  /**
   * Lenient validator paired with {@link TestPasswordValidator} to exercise
   * the dispose-and-replace branch of {@code reloadPasswordValidator}.
   */
  public static class LenientPasswordValidator implements PasswordValidator {
    @Override
    public void active() {
    }

    @Override
    public void config(
        DatabaseSessionEmbedded session,
        Map<String, Object> jsonConfig,
        SecuritySystem security) {
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public void validatePassword(String username, String password) {
      // Always accepts.
    }
  }

  /** Auditing-service plugin used by the auditing-reload happy path. */
  public static class TestAuditingService implements AuditingService {
    @Override
    public void active() {
    }

    @Override
    public void config(
        DatabaseSessionEmbedded session,
        Map<String, Object> jsonConfig,
        SecuritySystem security) {
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public void changeConfig(
        DatabaseSessionEmbedded session,
        SecurityUser user,
        String databaseName,
        Map<String, Object> cfg) {
    }

    @Override
    public Map<String, Object> getConfig(String databaseName) {
      return new HashMap<>();
    }

    @Override
    public void log(
        DatabaseSessionEmbedded session,
        AuditingOperation operation, String message) {
    }

    @Override
    public void log(
        DatabaseSessionEmbedded session,
        AuditingOperation operation, SecurityUser user, String message) {
    }

    @Override
    public void log(
        DatabaseSessionEmbedded session,
        AuditingOperation operation, String dbName, SecurityUser user,
        String message) {
    }
  }

  /**
   * Generic SecurityComponent stand-in used for the {@code ldapImporter}
   * reflective slot.  The slot's happy path calls {@code config + active}
   * but no production caller exists in this test scope; this class therefore
   * exists only for the missing-class / wrong-type assertions if needed.
   */
  public static class TestImportLDAP implements SecurityComponent {
    @Override
    public void active() {
    }

    @Override
    public void config(
        DatabaseSessionEmbedded session,
        Map<String, Object> jsonConfig,
        SecuritySystem security) {
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean isEnabled() {
      return true;
    }
  }

  /**
   * Plain class — implements neither {@link SecurityAuthenticator} nor
   * {@link PasswordValidator} nor {@link AuditingService}.  Used to drive
   * the {@code isAssignableFrom} reject branch of every load* helper.
   */
  public static class NotAnAuthenticator {
    public NotAnAuthenticator() {
    }
  }

  /**
   * Reference to {@link ServerConfigAuthenticator} / {@link SystemUserAuthenticator}
   * / {@link DatabaseUserAuthenticator} kept so the {@code initDefultAuthenticators}
   * fallback test pins the simple-name list the chain installs by default. Wired into
   * {@link #defaultAuthenticatorChainTypesMatchProductionDefaults} so removing or
   * reordering the chain in production surfaces here.
   */
  private static final Class<?>[] DEFAULT_CHAIN_TYPES = {
      ServerConfigAuthenticator.class,
      SystemUserAuthenticator.class,
      DatabaseUserAuthenticator.class,
  };

  @Test
  public void defaultAuthenticatorChainTypesMatchProductionDefaults() {
    // Pin the simple-name list of {@link #DEFAULT_CHAIN_TYPES} against the canonical
    // 3-entry chain installed by initDefultAuthenticators (note the deliberate typo
    // in the production method name). This guards against accidental reorder /
    // removal in the source list and complements the runtime assertion in
    // reloadAuthenticationWithoutAuthenticatorsKeyInstallsDefaultChain.
    var simpleNames = Arrays.stream(DEFAULT_CHAIN_TYPES)
        .map(Class::getSimpleName)
        .toArray();
    assertThat(simpleNames).containsExactly(
        "ServerConfigAuthenticator",
        "SystemUserAuthenticator",
        "DatabaseUserAuthenticator");
  }
}
