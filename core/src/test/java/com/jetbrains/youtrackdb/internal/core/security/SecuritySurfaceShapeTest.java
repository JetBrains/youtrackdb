package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Security;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Cheap fold-in shape pins for the small-surface security types listed in Step 1 (A8):
 * {@link Security} interface, {@link SecurityComponent} interface, {@link DefaultSecurityConfig}
 * (concrete), {@link Syslog} interface, {@link AuditingOperation} enum, and
 * {@link AuditingService} interface. These are not invasive enough to deserve their own
 * dedicated test classes, but each is part of a contract that downstream modules
 * (server, driver, integration tests) compile against — pinning the shape here surfaces
 * accidental signature changes during a refactor.
 */
public class SecuritySurfaceShapeTest {

  @Test
  public void securityInterfaceMustBePresent() {
    // Security is a deprecated facade with a small set of authenticate/allow methods.
    // Pin the type and a couple of representative methods.
    assertThat(Security.class.isInterface()).isTrue();
    assertThat(Arrays.stream(Security.class.getDeclaredMethods())
        .anyMatch(m -> m.getName().equals("authenticate"))).isTrue();
  }

  @Test
  public void securityComponentMustDeclareLifecycleMethods() throws Exception {
    // active() and dispose() are the lifecycle hooks that DefaultSecuritySystem invokes;
    // removing either would silently break component registration. Pin both.
    final var active = SecurityComponent.class.getDeclaredMethod("active");
    final var dispose = SecurityComponent.class.getDeclaredMethod("dispose");
    final var enabled = SecurityComponent.class.getDeclaredMethod("isEnabled");
    final var config =
        SecurityComponent.class.getDeclaredMethod(
            "config",
            DatabaseSessionEmbedded.class,
            Map.class,
            SecuritySystem.class);
    assertThat(active.getReturnType()).isEqualTo(void.class);
    assertThat(dispose.getReturnType()).isEqualTo(void.class);
    assertThat(enabled.getReturnType()).isEqualTo(boolean.class);
    assertThat(config.getReturnType()).isEqualTo(void.class);
  }

  @Test
  public void defaultSecurityConfigGetSyslogMustThrow() {
    // The concrete default returns "no syslog configured" by throwing on getSyslog(); this
    // is the observable contract that DefaultSecuritySystem.audit() relies on to decide
    // whether to emit syslog events at all.
    final var cfg = new DefaultSecurityConfig();
    assertThatThrownBy(cfg::getSyslog).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void defaultSecurityConfigGetConfigurationFileMustReturnNull() {
    // null indicates "no JSON config" — DefaultSecuritySystem treats that as "use defaults".
    final var cfg = new DefaultSecurityConfig();
    assertThat(cfg.getConfigurationFile()).isNull();
  }

  @Test
  public void defaultSecurityConfigImplementsSecurityConfig() {
    assertThat(SecurityConfig.class.isAssignableFrom(DefaultSecurityConfig.class)).isTrue();
  }

  @Test
  public void syslogInterfaceMustExposeThreeLogOverloads() {
    // The 1-, 2-, and 3-arg log overloads form the contract Syslog implementations honour.
    final var methods = Syslog.class.getDeclaredMethods();
    final var arities =
        Arrays.stream(methods)
            .filter(m -> m.getName().equals("log"))
            .map(m -> m.getParameterCount())
            .sorted()
            .toList();
    assertThat(arities).containsExactly(2, 3, 4);
  }

  @Test
  public void auditingOperationEnumMustCarryByteAndStringForms() {
    // Each enum constant carries (byte tag, string label); getByByte returns UNSPECIFIED for
    // unknown byte values. Pinning the round-trip per known constant catches accidental
    // re-numbering. CREATED/UPDATED/DELETED reuse RecordOperation.* constants.
    assertThat(AuditingOperation.CREATED.getByte()).isEqualTo(RecordOperation.CREATED);
    assertThat(AuditingOperation.UPDATED.getByte()).isEqualTo(RecordOperation.UPDATED);
    assertThat(AuditingOperation.DELETED.getByte()).isEqualTo(RecordOperation.DELETED);
    assertThat(AuditingOperation.UNSPECIFIED.getByte()).isEqualTo((byte) -1);
    assertThat(AuditingOperation.CHANGED_PWD.getByte()).isEqualTo((byte) 12);

    // String-form is the lower-camel-case label rendered through toString().
    assertThat(AuditingOperation.CREATED.toString()).isEqualTo("created");
    assertThat(AuditingOperation.RELOADEDSECURITY.toString()).isEqualTo("reloadedSecurity");
  }

  @Test
  public void auditingOperationGetByByteMustReturnUnspecifiedForUnknown() {
    // Unknown byte tag -> UNSPECIFIED (defensive default; never throws).
    assertThat(AuditingOperation.getByByte((byte) 99)).isEqualTo(AuditingOperation.UNSPECIFIED);
  }

  @Test
  public void auditingOperationGetByByteMustRoundTripKnown() {
    // Every enum constant must round-trip through getByByte (unique tags, no aliasing).
    for (final var op : AuditingOperation.values()) {
      // UNSPECIFIED uses tag -1 (literal byte); LOADED uses 0; the rest are distinct.
      // getByByte returns the *first* matching constant, but each tag is unique here.
      assertThat(AuditingOperation.getByByte(op.getByte())).isEqualTo(op);
    }
  }

  @Test
  public void auditingServiceMustExposeChangeConfigAndLogOverloads() {
    // AuditingService extends SecurityComponent and adds 3 log overloads + getConfig +
    // changeConfig. Pin both the inheritance and the method-name set.
    assertThat(SecurityComponent.class.isAssignableFrom(AuditingService.class)).isTrue();
    final var names =
        Arrays.stream(AuditingService.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertThat(names).contains("changeConfig", "getConfig", "log");
  }
}
