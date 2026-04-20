/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.sql.method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLMethodMultiValue;
import com.jetbrains.youtrackdb.internal.core.sql.functions.conversion.SQLMethodAsDate;
import com.jetbrains.youtrackdb.internal.core.sql.functions.conversion.SQLMethodAsDateTime;
import com.jetbrains.youtrackdb.internal.core.sql.functions.conversion.SQLMethodAsDecimal;
import com.jetbrains.youtrackdb.internal.core.sql.functions.conversion.SQLMethodConvert;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLMethodExclude;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLMethodInclude;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLMethodAppend;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLMethodHash;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLMethodLength;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLMethodReplace;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLMethodRight;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLMethodSubString;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLMethodToJSON;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsBoolean;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsFloat;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsInteger;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsList;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsLong;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsMap;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsSet;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodAsString;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodContains;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodField;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodFormat;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodFunctionDelegate;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodIndexOf;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodJavaType;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodKeys;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodLastIndexOf;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodNormalize;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodPrefix;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodRemove;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodRemoveAll;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodSize;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodSplit;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodToLowerCase;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodToUpperCase;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodTrim;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodType;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodValues;
import com.jetbrains.youtrackdb.internal.core.sql.method.sequence.SQLMethodCurrent;
import com.jetbrains.youtrackdb.internal.core.sql.method.sequence.SQLMethodNext;
import com.jetbrains.youtrackdb.internal.core.sql.method.sequence.SQLMethodReset;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Tests for {@link DefaultSQLMethodFactory} — the built-in registry of SQL method implementations
 * consumed by {@link com.jetbrains.youtrackdb.internal.core.sql.SQLEngine#getMethod(String)}.
 * Each test either instantiates a fresh
 * {@code DefaultSQLMethodFactory} (the production default) or calls {@code register} on it. None
 * of them touch a database session: the factory is a pure in-memory HashMap and every public
 * method has a computable signature.
 *
 * <p>Covered branches and contracts:
 *
 * <ul>
 *   <li>Every built-in NAME constant is reachable through {@code hasMethod} and
 *       {@code createMethod} — pinned via a single {@link #ALL_NAMES} array mirroring the
 *       production registration block.</li>
 *   <li>{@code register} / {@code hasMethod} lowercase keys via {@link Locale#ENGLISH}, so both
 *       case-insensitive reads AND Turkish-locale-neutral writes are exercised.</li>
 *   <li>{@code createMethod} is case-SENSITIVE — uppercase input for a lowercased entry raises
 *       {@link CommandExecutionException}. Pinned as a WHEN-FIXED regression (see
 *       {@link #createMethodIsCaseSensitiveWhileHasMethodIsCaseInsensitive}).</li>
 *   <li>Unknown-method dispatch throws {@link CommandExecutionException} with a message
 *       containing the requested name.</li>
 *   <li>Instance registrations reuse the same object (SQLMethodSize); class registrations
 *       reflectively instantiate a fresh object per call ({@link SQLMethodFunctionDelegate}).</li>
 *   <li>{@code getMethodNames} is the live key-view of the underlying map (keys are lowercase).</li>
 *   <li>{@code register} overwrites existing entries — pins map-put semantics.</li>
 *   <li>{@link #methodsBackingMapIsPlainHashMapWhenFixedConvertToConcurrent} pins that the
 *       backing map is a plain {@link HashMap} — a WHEN-FIXED regression for Track 22's
 *       conversion to {@link java.util.concurrent.ConcurrentHashMap} (paired with the equivalent
 *       race in {@code CustomSQLFunctionFactory}).</li>
 * </ul>
 *
 * <p>Tagged {@link SequentialTest} because the factory is instantiated per test but the class is
 * SPI-registered (via {@link SQLEngine}) in every other test's JVM fork. {@link FixMethodOrder}
 * keeps the registration-mutating test ({@link #zzzRegisterOverwritesExistingEntryForSameKey})
 * after read-only tests so mutations never pollute order-sensitive peer checks inside the same
 * class.
 */
@Category(SequentialTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DefaultSQLMethodFactoryTest {

  /**
   * Every method name the production factory registers. Mirrors
   * {@link DefaultSQLMethodFactory#DefaultSQLMethodFactory()} verbatim — keeping it in one place
   * makes it trivial to spot missing or duplicated entries by diffing this array against the
   * source constructor.
   */
  private static final String[] ALL_NAMES = {
      SQLMethodAppend.NAME,
      SQLMethodAsBoolean.NAME,
      SQLMethodAsDate.NAME,
      SQLMethodAsDateTime.NAME,
      SQLMethodAsDecimal.NAME,
      SQLMethodAsFloat.NAME,
      SQLMethodAsInteger.NAME,
      SQLMethodAsList.NAME,
      SQLMethodAsLong.NAME,
      SQLMethodAsMap.NAME,
      SQLMethodAsSet.NAME,
      SQLMethodContains.NAME,
      SQLMethodAsString.NAME,
      SQLMethodCharAt.NAME,
      SQLMethodConvert.NAME,
      SQLMethodExclude.NAME,
      SQLMethodField.NAME,
      SQLMethodFormat.NAME,
      SQLMethodFunctionDelegate.NAME,
      SQLMethodHash.NAME,
      SQLMethodInclude.NAME,
      SQLMethodIndexOf.NAME,
      SQLMethodJavaType.NAME,
      SQLMethodKeys.NAME,
      SQLMethodLastIndexOf.NAME,
      SQLMethodLeft.NAME,
      SQLMethodLength.NAME,
      SQLMethodMultiValue.NAME,
      SQLMethodNormalize.NAME,
      SQLMethodPrefix.NAME,
      SQLMethodRemove.NAME,
      SQLMethodRemoveAll.NAME,
      SQLMethodReplace.NAME,
      SQLMethodRight.NAME,
      SQLMethodSize.NAME,
      SQLMethodSplit.NAME,
      SQLMethodToLowerCase.NAME,
      SQLMethodToUpperCase.NAME,
      SQLMethodTrim.NAME,
      SQLMethodType.NAME,
      SQLMethodSubString.NAME,
      SQLMethodToJSON.NAME,
      SQLMethodValues.NAME,
      SQLMethodCurrent.NAME,
      SQLMethodNext.NAME,
      SQLMethodReset.NAME,
  };

  private DefaultSQLMethodFactory factory;

  @Before
  public void init() {
    factory = new DefaultSQLMethodFactory();
  }

  // ---------------------------------------------------------------------------
  // Known-name coverage — pins the registration block completeness.
  // ---------------------------------------------------------------------------

  @Test
  public void allRegisteredNamesReportedByHasMethod() {
    for (var name : ALL_NAMES) {
      assertTrue(
          "hasMethod must be true for registered: " + name,
          factory.hasMethod(name));
    }
  }

  @Test
  public void allRegisteredNamesCreatableViaCreateMethodExceptFunction() {
    // All NAME constants are stored lowercase by the production registration block, so
    // createMethod (which does NOT lowercase) finds them when the constant is the canonical
    // lowercase form. The ONE exception is SQLMethodFunctionDelegate.NAME ("function"): it is
    // registered as a Class<?>, but the class has NO no-arg constructor — its only ctor takes
    // a wrapped SQLFunction. createMethod's reflective `.newInstance()` path therefore throws
    // for "function". Pin the asymmetry as a WHEN-FIXED regression: either (a) give
    // SQLMethodFunctionDelegate a no-arg ctor + a post-hoc setFunction() / setFunction(null)
    // sentinel, or (b) remove SQLMethodFunctionDelegate from the factory and invoke it
    // directly from SQLFilterItemAbstract (the only real production caller).
    for (var name : ALL_NAMES) {
      if (name.equals(SQLMethodFunctionDelegate.NAME)) {
        // WHEN-FIXED: flip this branch to call createMethod(name) and assertNotNull.
        try {
          factory.createMethod(name);
          fail(
              "WHEN-FIXED: expected CommandExecutionException — SQLMethodFunctionDelegate has"
                  + " no no-arg constructor and cannot be instantiated via the factory's"
                  + " reflective path");
        } catch (CommandExecutionException expected) {
          // The wrapException chain preserves the reflective failure — pin that visibility so a
          // regression that silently catches-and-returns-null would be immediately caught.
          assertTrue(
              "message should reference SQLMethodFunctionDelegate: " + expected.getMessage(),
              expected.getMessage().contains("SQLMethodFunctionDelegate"));
        }
        continue;
      }
      var m = factory.createMethod(name);
      assertNotNull("createMethod must return non-null for: " + name, m);
    }
  }

  @Test
  public void getMethodNamesMatchesRegisteredSet() {
    // The factory's advertised name set must exactly equal ALL_NAMES. Set-based comparison
    // catches both missing entries and unintended additions (which a size-only check would miss
    // if two errors cancelled out).
    var expected = new HashSet<String>();
    for (var name : ALL_NAMES) {
      expected.add(name.toLowerCase(Locale.ENGLISH));
    }
    var actual = new HashSet<>(factory.getMethodNames());
    assertEquals(
        "getMethodNames must exactly match the ALL_NAMES set registered by the ctor",
        expected,
        actual);
  }

  @Test
  public void allNameConstantsAreAlreadyLowerCase() {
    // Production NAME constants are stored lowercase by convention. If a regression introduced
    // an uppercase or mixed-case constant, createMethod(NAME) would throw (because the map is
    // keyed lowercase but createMethod is case-sensitive) — pin that convention explicitly so
    // we catch the cause, not just the downstream symptom.
    for (var name : ALL_NAMES) {
      assertEquals(
          "NAME constant should be lowercase: " + name, name.toLowerCase(Locale.ENGLISH), name);
    }
  }

  // ---------------------------------------------------------------------------
  // Case handling — register/hasMethod lower, createMethod does NOT.
  // ---------------------------------------------------------------------------

  @Test
  public void hasMethodIsCaseInsensitive() {
    // register() lowercases on put; hasMethod() lowercases on get. So uppercase/mixed lookups
    // succeed even though the map key is lowercase.
    assertTrue(factory.hasMethod("SIZE"));
    assertTrue(factory.hasMethod("SizE"));
    assertTrue(factory.hasMethod("size"));
  }

  @Test
  public void hasMethodFalseForUnknownName() {
    assertFalse(factory.hasMethod("definitelyNotRegistered"));
  }

  @Test
  public void createMethodIsCaseSensitiveWhileHasMethodIsCaseInsensitive() {
    // WHEN-FIXED: DefaultSQLMethodFactory.createMethod(name) must lowercase `name` before the
    // map lookup to match register/hasMethod semantics. When fixed, flip this test to
    // `assertNotNull(factory.createMethod("SIZE"));` and delete the catch block.
    //
    // Current observable inconsistency:
    //   hasMethod("SIZE") == true    (lowercases internally)
    //   createMethod("SIZE")         throws CommandExecutionException("Unknown method name: SIZE")
    //
    // SQLEngine.getMethod masks the bug by lowercasing before dispatch, but any direct factory
    // caller (or a future factory) sees the inconsistency. T6 in Track 7 review.
    assertTrue("pre-condition: hasMethod is case-insensitive", factory.hasMethod("SIZE"));
    try {
      factory.createMethod("SIZE");
      fail("expected CommandExecutionException — createMethod is case-sensitive");
    } catch (CommandExecutionException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message must surface the requested (uppercase) name so callers can recognise the bug; saw: "
              + e.getMessage(),
          e.getMessage().contains("Unknown method name: SIZE"));
    }
    // Proof that the lowercased variant works — rules out "map is broken" as an alternative
    // explanation.
    assertNotNull("lowercased dispatch must succeed", factory.createMethod("size"));
  }

  // ---------------------------------------------------------------------------
  // Instance vs Class registration semantics.
  // ---------------------------------------------------------------------------

  @Test
  public void createMethodReturnsSameInstanceForInstanceRegistration() {
    // SQLMethodSize is registered as `new SQLMethodSize()` — two createMethod calls must return
    // the SAME object (instance-level caching).
    var a = factory.createMethod(SQLMethodSize.NAME);
    var b = factory.createMethod(SQLMethodSize.NAME);
    assertNotNull(a);
    assertNotNull(b);
    assertSame("Instance-registered methods must be reused", a, b);
  }

  @Test
  public void createMethodReturnsFreshInstanceForClassRegistration() {
    // The production factory's only Class<?> registration (SQLMethodFunctionDelegate) cannot be
    // instantiated via the reflective path (see
    // {@link #allRegisteredNamesCreatableViaCreateMethodExceptFunction}). To pin the
    // "fresh-instance" semantics of the Class<?> branch itself, register a test-only class
    // that DOES have a no-arg ctor and assert two createMethod calls produce distinct objects.
    //
    // This is a structural test of the factory's dispatch logic — independent of which
    // production class happens to be class-registered.
    var className = "classreg_" + UUID.randomUUID().toString().replace("-", "_");
    factory.register(className, FreshPerCallMethod.class);

    var a = factory.createMethod(className);
    var b = factory.createMethod(className);
    assertNotNull(a);
    assertNotNull(b);
    assertTrue(a instanceof FreshPerCallMethod);
    assertTrue(b instanceof FreshPerCallMethod);
    assertNotSame("Class-registered methods must produce fresh instances per call", a, b);
  }

  @Test
  public void createMethodReturnsExpectedConcreteType() {
    // Spot check: the production map must deliver the exact concrete class advertised by the
    // registration line. A regression that wired `SQLMethodLeft.class` into the "size" slot
    // would still satisfy the "non-null" gate of allRegisteredNamesCreatableViaCreateMethod but
    // would fail this instanceof assertion.
    assertTrue(factory.createMethod(SQLMethodSize.NAME) instanceof SQLMethodSize);
    assertTrue(factory.createMethod(SQLMethodLeft.NAME) instanceof SQLMethodLeft);
    assertTrue(factory.createMethod(SQLMethodTrim.NAME) instanceof SQLMethodTrim);
  }

  // ---------------------------------------------------------------------------
  // Unknown-method dispatch
  // ---------------------------------------------------------------------------

  @Test
  public void createMethodUnknownNameThrowsCommandExecutionWithName() {
    try {
      factory.createMethod("definitelyNotRegistered");
      fail("expected CommandExecutionException for unknown method");
    } catch (CommandExecutionException e) {
      assertNotNull(e.getMessage());
      assertTrue(
          "message must include the unknown name for debuggability, saw: " + e.getMessage(),
          e.getMessage().contains("definitelyNotRegistered"));
    }
  }

  // ---------------------------------------------------------------------------
  // register() mutation semantics.
  // ---------------------------------------------------------------------------

  @Test
  public void registerNewEntryBecomesLookupable() {
    // UUID suffix defends against accidental collisions if any prior test registered the same
    // synthetic name. Since @FixMethodOrder is ascending, this test runs BEFORE
    // zzzRegisterOverwritesExistingEntryForSameKey — any permanent mutation it introduces is
    // bounded to that one key.
    var synthetic =
        "factorytest_" + UUID.randomUUID().toString().replace("-", "_") + "_new";
    var impl = new StubMethod();
    factory.register(synthetic, impl);
    assertTrue(factory.hasMethod(synthetic));
    assertSame(impl, factory.createMethod(synthetic));
  }

  @Test
  public void registerStoresLowercasedKey() {
    // register() lowercases via Locale.ENGLISH regardless of input case. Pin the key material is
    // lowercase by reading getMethodNames (which returns the raw keys).
    var synthetic =
        "MixedCaseTest_" + UUID.randomUUID().toString().replace("-", "_");
    factory.register(synthetic, new StubMethod());
    var lower = synthetic.toLowerCase(Locale.ENGLISH);
    assertTrue(
        "getMethodNames must contain the lowercased form: " + lower,
        factory.getMethodNames().contains(lower));
    assertFalse(
        "mixed-case form must NOT be stored directly: " + synthetic,
        factory.getMethodNames().contains(synthetic));
  }

  @Test
  public void zzzRegisterOverwritesExistingEntryForSameKey() {
    // Test runs last (zzz-prefix + FixMethodOrder) — it mutates a built-in entry and then uses
    // the ALREADY-BUILT factory field; FakeNameTest's fresh factory in @Before shields peer
    // tests from leaking state via the `methods` field. The JVM-wide SPI cache is ALSO immune
    // because DefaultSQLMethodFactory instances don't share state.
    //
    // Behavioural contract: register() calls Map.put(), which overwrites. Pinning this is the
    // only way to detect a regression that silently converts register() into "first-wins" or
    // "no-op if exists" semantics — either of which would break the SPI late-binder pattern
    // used by CustomSQLFunctionFactory and co.
    var replacement = new StubMethod();
    factory.register(SQLMethodSize.NAME, replacement);
    assertSame(
        "register() must overwrite existing entries (not first-wins)",
        replacement,
        factory.createMethod(SQLMethodSize.NAME));
  }

  // ---------------------------------------------------------------------------
  // HashMap-race pin — WHEN-FIXED regression.
  // ---------------------------------------------------------------------------

  @Test
  public void methodsBackingMapIsPlainHashMapWhenFixedConvertToConcurrent() throws Exception {
    // WHEN-FIXED: Convert `methods` to a ConcurrentHashMap (or Collections.synchronizedMap) to
    // eliminate the HashMap race that leaves register()/createMethod() concurrently unsafe.
    // Paired with the parallel fix for CustomSQLFunctionFactory.FUNCTIONS. When fixed, flip the
    // assertion below to `assertTrue(mapField.get(factory) instanceof ConcurrentMap)`.
    //
    // The race is not reproducible with a deterministic unit test, so this test verifies the
    // *structural* invariant (field type) rather than the data race itself. T4/T10 in Track 7
    // review. Twin of CustomSQLFunctionFactoryTest's pin in Track 6.
    Field mapField = DefaultSQLMethodFactory.class.getDeclaredField("methods");
    mapField.setAccessible(true);
    Object map = mapField.get(factory);
    assertNotNull(map);
    assertTrue(
        "WHEN-FIXED: methods field should become a ConcurrentHashMap. Currently: "
            + map.getClass().getName(),
        map instanceof HashMap);
    // Narrow further: the declared field type must also be Map (not HashMap), because the
    // declared type is what SPI consumers observe. A future refactor that tightens the declared
    // type into HashMap would obscure the race and should trip this assertion.
    assertEquals(
        "Declared field type should stay generic (Map) so it can be swapped to ConcurrentMap"
            + " without a public API break",
        Map.class,
        mapField.getType());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link SQLMethod} with a PUBLIC no-arg constructor — registered as a
   * {@code Class<?>} to exercise the fresh-instance-per-call branch of {@code createMethod}
   * that the production {@code SQLMethodFunctionDelegate} registration cannot exercise (no
   * no-arg ctor).
   */
  public static final class FreshPerCallMethod implements SQLMethod {

    public FreshPerCallMethod() {
      // Intentionally public no-arg ctor — required by Class.newInstance reflective path.
    }

    @Override
    public String getName() {
      return "fresh";
    }

    @Override
    public String getSyntax() {
      return "fresh()";
    }

    @Override
    public int getMinParams() {
      return 0;
    }

    @Override
    public int getMaxParams(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session) {
      return 0;
    }

    @Override
    public Object execute(
        Object iThis,
        com.jetbrains.youtrackdb.internal.core.query.Result iCurrentRecord,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext iContext,
        Object ioResult,
        Object[] iParams) {
      return ioResult;
    }

    @Override
    public boolean evaluateParameters() {
      return false;
    }

    @Override
    public int compareTo(SQLMethod o) {
      return this.getName().compareTo(o.getName());
    }
  }

  /** Minimal {@link SQLMethod} used to seed register() / createMethod() mutations in tests. */
  private static final class StubMethod implements SQLMethod {

    @Override
    public String getName() {
      return "stub";
    }

    @Override
    public String getSyntax() {
      return "stub()";
    }

    @Override
    public int getMinParams() {
      return 0;
    }

    @Override
    public int getMaxParams(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session) {
      return 0;
    }

    @Override
    public Object execute(
        Object iThis,
        com.jetbrains.youtrackdb.internal.core.query.Result iCurrentRecord,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext iContext,
        Object ioResult,
        Object[] iParams) {
      return ioResult;
    }

    @Override
    public boolean evaluateParameters() {
      return false;
    }

    @Override
    public int compareTo(SQLMethod o) {
      return this.getName().compareTo(o.getName());
    }
  }
}
