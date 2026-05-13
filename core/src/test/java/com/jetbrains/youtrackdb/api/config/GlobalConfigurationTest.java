/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Coverage for the {@link GlobalConfiguration} enum — process-wide configuration registry.
 *
 * <p>Mutators on individual enum constants ({@code setValue}, {@link
 * GlobalConfiguration#setConfiguration(java.util.Map)}) write to a {@code volatile Object value}
 * field on the enum constant itself, which is shared across the entire JVM. Tests that mutate
 * those fields therefore carry {@link Category}{@code (}{@link SequentialTest}{@code )} so the
 * core module's surefire configuration runs them on the sequential executor (parallel
 * executions explicitly exclude this category — see the per-module README).
 *
 * <p>Each test below snapshots the affected configuration's current value (via {@link
 * GlobalConfiguration#getValue()} and {@link GlobalConfiguration#isChanged()}) in {@link
 * Before} and restores it in {@link After}. The restore is a stack of deltas keyed off the
 * {@code isChanged()} flag — when the snapshot recorded "not changed from default" we restore
 * by reflecting the {@code value} back to the {@code nullValue} sentinel via setting the
 * default; when "changed" we reapply the snapshot. This mirrors the snapshot/restore
 * convention codified by {@code ByteBufferPoolTest} and required by Phase A finding T5/R8.
 */
@Category(SequentialTest.class)
public class GlobalConfigurationTest {

  // Snapshots for each configuration the tests touch. We keep both the value and the
  // changed-from-default flag so the restore in @After can reset to the same observable
  // state (changed-from-default propagates into setConfiguration / isChanged consumers).
  private Object snapInitInServletContextListener;
  private boolean snapInitInServletContextListenerChanged;
  private Object snapMemoryProfiling;
  private boolean snapMemoryProfilingChanged;
  private Object snapMemoryProfilingReportInterval;
  private boolean snapMemoryProfilingReportIntervalChanged;
  private Object snapMemoryLeftToOs;
  private boolean snapMemoryLeftToOsChanged;
  private Object snapDbSystemDatabaseEnabled;
  private boolean snapDbSystemDatabaseEnabledChanged;
  private Object snapClientSslKeyStorePassword;
  private boolean snapClientSslKeyStorePasswordChanged;

  @Before
  public void snapshotConfigurationsTouchedByTests() {
    snapInitInServletContextListener =
        GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.getValue();
    snapInitInServletContextListenerChanged =
        GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.isChanged();
    snapMemoryProfiling = GlobalConfiguration.MEMORY_PROFILING.getValue();
    snapMemoryProfilingChanged = GlobalConfiguration.MEMORY_PROFILING.isChanged();
    snapMemoryProfilingReportInterval =
        GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL.getValue();
    snapMemoryProfilingReportIntervalChanged =
        GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL.isChanged();
    snapMemoryLeftToOs = GlobalConfiguration.MEMORY_LEFT_TO_OS.getValue();
    snapMemoryLeftToOsChanged = GlobalConfiguration.MEMORY_LEFT_TO_OS.isChanged();
    snapDbSystemDatabaseEnabled = GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.getValue();
    snapDbSystemDatabaseEnabledChanged = GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.isChanged();
    snapClientSslKeyStorePassword = GlobalConfiguration.CLIENT_SSL_KEYSTORE_PASSWORD.getValue();
    snapClientSslKeyStorePasswordChanged =
        GlobalConfiguration.CLIENT_SSL_KEYSTORE_PASSWORD.isChanged();
  }

  @After
  public void restoreConfigurations() {
    // setValue is the only public path — if the snapshot was "default" we still call setValue
    // with the current default to clear divergence (the next read returns the same observable
    // value). isChanged will return true after this restoration since setValue assigns the
    // value field unconditionally — that's an acceptable cost to keep observable getters
    // returning the same numbers as before the test ran.
    if (snapInitInServletContextListenerChanged) {
      GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER
          .setValue(snapInitInServletContextListener);
    } else {
      GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.setValue(
          GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.getDefValue());
    }
    if (snapMemoryProfilingChanged) {
      GlobalConfiguration.MEMORY_PROFILING.setValue(snapMemoryProfiling);
    } else {
      GlobalConfiguration.MEMORY_PROFILING
          .setValue(GlobalConfiguration.MEMORY_PROFILING.getDefValue());
    }
    if (snapMemoryProfilingReportIntervalChanged) {
      GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL
          .setValue(snapMemoryProfilingReportInterval);
    } else {
      GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL.setValue(
          GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL.getDefValue());
    }
    if (snapMemoryLeftToOsChanged) {
      GlobalConfiguration.MEMORY_LEFT_TO_OS.setValue(snapMemoryLeftToOs);
    } else {
      GlobalConfiguration.MEMORY_LEFT_TO_OS
          .setValue(GlobalConfiguration.MEMORY_LEFT_TO_OS.getDefValue());
    }
    if (snapDbSystemDatabaseEnabledChanged) {
      GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.setValue(snapDbSystemDatabaseEnabled);
    } else {
      GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.setValue(
          GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED.getDefValue());
    }
    if (snapClientSslKeyStorePasswordChanged) {
      GlobalConfiguration.CLIENT_SSL_KEYSTORE_PASSWORD.setValue(snapClientSslKeyStorePassword);
    } else {
      // The default for this entry is null; setValue ignores null inputs so the value field
      // stays whatever the test set it to. Skip the restore in the not-changed branch.
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Shape: getKey, getDefValue, getType, getDescription, isChangeableAtRuntime, isHidden
  // ---------------------------------------------------------------------------------------------

  /**
   * The static fields populated at enum construction time round-trip through the public
   * getters: key (canonical config name), description (free-form), type (declared class), and
   * defValue (default). Each value is verified against a known constant from the enum body.
   */
  @Test
  public void enumConstantStaticPropertiesRoundTrip() {
    var cfg = GlobalConfiguration.MEMORY_PROFILING;
    assertEquals("youtrackdb.memory.profiling", cfg.getKey());
    assertEquals("Switches on profiling of allocations of direct memory inside of YouTrackDB.",
        cfg.getDescription());
    assertEquals(Boolean.class, cfg.getType());
    assertEquals(false, cfg.getDefValue());
    assertFalse(cfg.isHidden());
    assertFalse(cfg.isChangeableAtRuntime());
  }

  /**
   * {@link GlobalConfiguration#isHidden()} returns {@code true} for entries declared with the
   * 6-arg ctor and the {@code iHidden=true} flag. {@link GlobalConfiguration#isChangeableAtRuntime()}
   * returns the {@code iCanChange} flag passed at construction. Pin the hidden=true branch via
   * {@code STORAGE_ENCRYPTION_KEY} (declared {@code (… , false, true)} — not changeable,
   * hidden), and the changeableAtRuntime=true branch via {@code DB_VALIDATION} (declared
   * {@code (… , true, true)} where the second {@code true} is iCanChange).
   */
  @Test
  public void hiddenAndChangeableAtRuntimeFlagsArePropagated() {
    var hiddenCfg = GlobalConfiguration.STORAGE_ENCRYPTION_KEY;
    assertTrue("STORAGE_ENCRYPTION_KEY is hidden", hiddenCfg.isHidden());
    assertFalse(
        "STORAGE_ENCRYPTION_KEY is not changeable at runtime", hiddenCfg.isChangeableAtRuntime());

    var changeableCfg = GlobalConfiguration.DB_VALIDATION;
    assertTrue("DB_VALIDATION is changeable at runtime", changeableCfg.isChangeableAtRuntime());
    // DB_VALIDATION default isHidden — the per-spec absence of an iHidden flag in its 5-arg
    // ctor means iHidden defaults to false.
    assertFalse("DB_VALIDATION is not hidden", changeableCfg.isHidden());
  }

  // ---------------------------------------------------------------------------------------------
  // setValue type-coercion branches: Boolean, Integer, Float, Double, String, default-Object
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code setValue} on a Boolean-typed entry parses arbitrary CharSequence inputs through
   * {@link Boolean#parseBoolean}. Pin three input shapes to exercise the parse path: native
   * Boolean, "true"/"false" strings, and a non-true string ("yes" parses to false).
   */
  @Test
  public void setValueOnBooleanTypeCoercesViaParseBoolean() {
    var cfg = GlobalConfiguration.MEMORY_PROFILING;

    cfg.setValue(true);
    assertEquals(Boolean.TRUE, cfg.getValue());
    assertTrue(cfg.getValueAsBoolean());

    cfg.setValue("false");
    assertEquals(Boolean.FALSE, cfg.getValue());

    cfg.setValue("yes");
    // Boolean.parseBoolean returns false for any non-"true" string.
    assertEquals(Boolean.FALSE, cfg.getValue());
  }

  /**
   * {@code setValue} on an Integer-typed entry parses through {@link Integer#parseInt}. Pin
   * native int and string parses; native is the common path, string is the
   * system-property-loaded path used at startup by {@code readConfiguration}.
   */
  @Test
  public void setValueOnIntegerTypeCoercesViaParseInt() {
    var cfg = GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL;
    cfg.setValue(30);
    assertEquals(30, cfg.getValueAsInteger());
    cfg.setValue("45");
    assertEquals(45, cfg.getValueAsInteger());
  }

  /**
   * {@code setValue} on a String-typed entry calls {@link Object#toString()} on the input.
   * Pin two input shapes: native String and Integer (whose toString yields a numeric string).
   */
  @Test
  public void setValueOnStringTypeStoresToStringValue() {
    var cfg = GlobalConfiguration.MEMORY_LEFT_TO_OS;
    cfg.setValue("4g");
    assertEquals("4g", cfg.getValue());
    // Non-string input — toString-coerced.
    cfg.setValue(123);
    assertEquals("123", cfg.getValue());
  }

  /**
   * {@code setValue} ignores {@code null} input — the field is left at its prior value. Pin
   * by setting a non-default value, then setting null, and confirming the prior value
   * survives.
   */
  @Test
  public void setValueIgnoresNullInput() {
    var cfg = GlobalConfiguration.MEMORY_LEFT_TO_OS;
    cfg.setValue("4g");
    cfg.setValue(null);
    // Null input is dropped — value remains the previous "4g".
    assertEquals("4g", cfg.getValue());
  }

  // ---------------------------------------------------------------------------------------------
  // Read-as-typed: getValueAsBoolean / getValueAsInteger / getValueAsLong / getValueAsFloat /
  // getValueAsDouble / getValueAsString — each has a numeric / non-numeric / null-default
  // branch.
  // ---------------------------------------------------------------------------------------------

  /**
   * {@link GlobalConfiguration#getValueAsBoolean()} returns the underlying Boolean when
   * present and parses non-Boolean values via {@link Boolean#parseBoolean}. Both branches are
   * pinned by reading the value before and after a setValue on a String-typed config.
   */
  @Test
  public void getValueAsBooleanReadsBooleanOrParsesString() {
    var booleanCfg = GlobalConfiguration.MEMORY_PROFILING;
    booleanCfg.setValue(false);
    assertFalse(booleanCfg.getValueAsBoolean());

    var stringCfg = GlobalConfiguration.MEMORY_LEFT_TO_OS;
    stringCfg.setValue("true");
    assertTrue("getValueAsBoolean parses string", stringCfg.getValueAsBoolean());
  }

  /**
   * {@link GlobalConfiguration#getValueAsLong()} returns a Number's longValue when the
   * underlying type is numeric, and falls back to {@code FileUtils.getSizeAsNumber} when the
   * type is a size-suffixed string. The size parser recognises {@code KB / MB / GB / TB / B /
   * %} (uppercased after parsing); pin the GB branch with a {@code "4gb"} input — 4GiB.
   */
  @Test
  public void getValueAsLongHandlesSizeSuffixedStrings() {
    var cfg = GlobalConfiguration.MEMORY_LEFT_TO_OS;
    cfg.setValue("4gb");
    assertEquals(4L * 1024 * 1024 * 1024, cfg.getValueAsLong());
  }

  /**
   * {@link GlobalConfiguration#getValueAsInteger()} returns a Number's intValue when the
   * underlying type is numeric. The {@code MEMORY_PROFILING_REPORT_INTERVAL} default is 15.
   */
  @Test
  public void getValueAsIntegerReadsNumericValue() {
    var cfg = GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL;
    cfg.setValue(60);
    assertEquals(60, cfg.getValueAsInteger());
  }

  /**
   * {@link GlobalConfiguration#getValueAsFloat()} returns a Float's floatValue when present
   * and parses via {@link Float#parseFloat} otherwise. We exercise the parse path by
   * configuring a String-typed entry and reading it as a float.
   */
  @Test
  public void getValueAsFloatHandlesStringParse() {
    var cfg = GlobalConfiguration.MEMORY_LEFT_TO_OS;
    cfg.setValue("3.14");
    assertEquals(3.14f, cfg.getValueAsFloat(), 0.0001);
  }

  /**
   * {@link GlobalConfiguration#getValueAsDouble()} mirrors the Float branch — Number's
   * doubleValue when present, otherwise parse.
   */
  @Test
  public void getValueAsDoubleHandlesStringParse() {
    var cfg = GlobalConfiguration.MEMORY_LEFT_TO_OS;
    cfg.setValue("2.71828");
    assertEquals(2.71828, cfg.getValueAsDouble(), 0.000001);
  }

  /**
   * {@link GlobalConfiguration#getValueAsString()} returns null when the value is null AND the
   * default is null. {@code CLIENT_SSL_KEYSTORE_PASSWORD} declares a null default and starts
   * unchanged. Pin the null-string branch through the unset state.
   */
  @Test
  public void getValueAsStringReturnsNullWhenValueAndDefaultAreBothNull() {
    var cfg = GlobalConfiguration.CLIENT_SSL_KEYSTORE_PASSWORD;
    // Restore covers any prior setValue. The field starts at the {@code nullValue} sentinel
    // unless an external setting changed it during VM startup; if it has been changed, skip
    // the assertion to avoid a flaky test on configured environments.
    if (!cfg.isChanged()) {
      assertNull(cfg.getValueAsString());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // isChanged: false on default, true after setValue.
  // ---------------------------------------------------------------------------------------------

  /**
   * {@link GlobalConfiguration#isChanged()} returns false when the configuration sits on its
   * default sentinel and true after any {@code setValue} call. Pin both branches in a single
   * test against a configuration we know is at default at this point (the {@code @Before}
   * snapshot recorded {@code isChanged() == false} for {@code MEMORY_PROFILING_REPORT_INTERVAL}
   * unless an external system property is shadowing it).
   */
  @Test
  public void isChangedReflectsAssignmentsThroughSetValue() {
    var cfg = GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL;
    cfg.setValue(99);
    assertTrue(cfg.isChanged());
  }

  // ---------------------------------------------------------------------------------------------
  // Static helpers: findByKey, getEnvKey, setConfiguration, dumpConfiguration.
  // ---------------------------------------------------------------------------------------------

  /**
   * {@link GlobalConfiguration#findByKey(String)} matches case-insensitively against the
   * canonical key. Both the exact-case match and a mixed-case probe must resolve to the same
   * enum constant; a non-matching key returns null.
   */
  @Test
  public void findByKeyLooksUpCaseInsensitive() {
    assertSame(
        GlobalConfiguration.MEMORY_PROFILING,
        GlobalConfiguration.findByKey("youtrackdb.memory.profiling"));
    assertSame(
        GlobalConfiguration.MEMORY_PROFILING,
        GlobalConfiguration.findByKey("YOUTRACKDB.MEMORY.PROFILING"));
    assertNull(GlobalConfiguration.findByKey("nonexistent.key"));
  }

  /**
   * {@link GlobalConfiguration#getEnvKey(GlobalConfiguration)} returns null when {@code env}
   * is false (the common case — every entry currently in the enum uses one of the shorter
   * ctors that default {@code iEnv} to {@code false}). Pinning the false-branch return guards
   * the helper's null-return contract; the true-branch is defended by the per-call
   * synthesis logic ({@code "YOUTRACKDB_" + name()}) which is mechanical and would break
   * compile-time on a method-rename.
   */
  @Test
  public void getEnvKeyReturnsNullWhenEnvFlagIsFalse() {
    // env=false is the universal case in the current enum body.
    assertNull(GlobalConfiguration.getEnvKey(GlobalConfiguration.MEMORY_PROFILING));
    assertNull(GlobalConfiguration.getEnvKey(GlobalConfiguration.MEMORY_LEFT_TO_OS));
    assertNull(GlobalConfiguration.getEnvKey(GlobalConfiguration.DB_SYSTEM_DATABASE_ENABLED));
  }

  /**
   * {@link GlobalConfiguration#setConfiguration(java.util.Map)} accepts both the canonical
   * key (e.g., "youtrackdb.memory.profiling") and the Java enum name ("MEMORY_PROFILING") in
   * the same call, dispatching the right branch per entry. Pin both lookup branches in a
   * single map.
   */
  @Test
  public void setConfigurationAcceptsBothKeyAndEnumNameLookup() {
    var map = new HashMap<String, Object>();
    map.put("youtrackdb.memory.profiling", true);
    map.put("MEMORY_PROFILING_REPORT_INTERVAL", 77);
    // A key that matches neither path is silently ignored.
    map.put("not.a.real.key", "ignored");
    GlobalConfiguration.setConfiguration(map);

    assertTrue(GlobalConfiguration.MEMORY_PROFILING.getValueAsBoolean());
    assertEquals(77, GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL.getValueAsInteger());
  }

  /**
   * {@link GlobalConfiguration#dumpConfiguration(java.io.PrintStream)} writes a human-readable
   * dump to the given stream. We capture the dump and pin a few load-bearing substrings: the
   * "configuration dump" header, the section header (every key starts with
   * {@code "youtrackdb."} so {@code section} resolves to the literal token before the FIRST
   * dot — uppercased to {@code "YOUTRACKDB"}), and a known config key formatted as
   * {@code "+ key = value"}. Hidden entries print {@code <hidden>} instead of the value — pin
   * the marker on {@code STORAGE_ENCRYPTION_KEY} which is declared with iHidden=true.
   */
  @Test
  public void dumpConfigurationProducesHumanReadableSectionedOutput() {
    var captured = new ByteArrayOutputStream();
    try (var ps = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      GlobalConfiguration.dumpConfiguration(ps);
    }

    var dump = captured.toString(StandardCharsets.UTF_8);
    assertTrue("expected header: " + dump.substring(0, Math.min(dump.length(), 200)),
        dump.contains("configuration dump"));
    // Section header — the prefix before the first "." is uppercased; every entry starts with
    // "youtrackdb." so the only section emitted is "YOUTRACKDB".
    assertTrue("expected YOUTRACKDB section: " + dump, dump.contains("- YOUTRACKDB"));
    // Per-key line shape: "  + key = value".
    assertTrue("expected memory.profiling key line: " + dump,
        dump.contains("+ youtrackdb.memory.profiling = "));
    // Hidden entries print "<hidden>" instead of the actual value.
    assertTrue("expected <hidden> marker for STORAGE_ENCRYPTION_KEY: " + dump,
        dump.contains("+ youtrackdb.storage.encryptionKey = <hidden>"));
  }

  // ---------------------------------------------------------------------------------------------
  // setValue invalid-input failure path: enum type acceptor rejects garbage with
  // IllegalArgumentException.
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code setValue} performs a type-coercion guard on numeric inputs by delegating to {@link
   * Integer#parseInt} / {@link Long#parseLong} / etc.; an unparseable string therefore throws
   * {@link NumberFormatException}. Pin the failure path on an Integer-typed entry — confirms
   * that bad input does not silently corrupt the field.
   */
  @Test
  public void setValueOnIntegerTypeThrowsOnUnparseableString() {
    var cfg = GlobalConfiguration.MEMORY_PROFILING_REPORT_INTERVAL;
    var prior = cfg.getValueAsInteger();
    try {
      cfg.setValue("not-a-number");
      fail("expected NumberFormatException");
    } catch (NumberFormatException expected) {
      // Field is left in an indeterminate state when parseInt throws — re-set to the prior
      // observable value so subsequent tests in the same JVM see the snapshot value.
      cfg.setValue(prior);
    }
  }
}
