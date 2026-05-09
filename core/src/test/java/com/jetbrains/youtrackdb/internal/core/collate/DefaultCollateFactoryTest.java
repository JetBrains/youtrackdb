/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.collate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import java.util.HashSet;
import java.util.Locale;
import java.util.ServiceLoader;
import org.junit.Test;

/**
 * Tests for the {@code core/collate} factory and helper-class surface.
 *
 * <p>Pins:
 * <ul>
 *   <li>{@link DefaultCollateFactory#getNames()} reports the two built-in
 *       lower-cased collate names ({@code "default"}, {@code "ci"}).</li>
 *   <li>{@link DefaultCollateFactory#getCollate(String)} routes case-insensitively
 *       to the registered instances and returns {@code null} on unknown names.</li>
 *   <li>The factory itself is reachable via the standard
 *       {@link java.util.ServiceLoader} contract using the
 *       {@code META-INF/services/com.jetbrains...CollateFactory} entry — the
 *       SPI loader path that {@code CollateFactoryAggregator} (and downstream
 *       SQL machinery) walks at runtime.</li>
 *   <li>Lightweight {@code getName} / {@code toString} pins for both built-in
 *       collates so a rename of either {@code NAME} constant flips a test
 *       (the constants are persisted into existing schemas, so the rename
 *       blast radius is large).</li>
 * </ul>
 */
public class DefaultCollateFactoryTest {

  // ---------------------------------------------------------------------------
  // Factory — getNames / getCollate
  // ---------------------------------------------------------------------------

  @Test
  public void getNamesReportsBothBuiltInCollatesUnderTheirLowerCasedNames() {
    var factory = new DefaultCollateFactory();
    var names = new HashSet<>(factory.getNames());
    // Names are lowercased on registration; the static initialiser registers
    // exactly two built-ins.
    assertEquals(2, names.size());
    assertTrue(names.contains(DefaultCollate.NAME.toLowerCase(Locale.ROOT)));
    assertTrue(names.contains(CaseInsensitiveCollate.NAME.toLowerCase(Locale.ROOT)));
  }

  @Test
  public void getCollateReturnsTheDefaultCollateForTheLowercaseDefaultName() {
    var factory = new DefaultCollateFactory();
    var collate = factory.getCollate(DefaultCollate.NAME);
    assertTrue("default name resolves to a DefaultCollate instance",
        collate instanceof DefaultCollate);
  }

  @Test
  public void getCollateIsCaseInsensitiveInLookup() {
    // The factory lower-cases the input via Locale.ROOT before the map lookup,
    // so any letter casing must resolve.
    var factory = new DefaultCollateFactory();
    assertTrue(factory.getCollate("DEFAULT") instanceof DefaultCollate);
    assertTrue(factory.getCollate("Default") instanceof DefaultCollate);
    assertTrue(factory.getCollate("CI") instanceof CaseInsensitiveCollate);
  }

  @Test
  public void getCollateReturnsNullForUnknownNames() {
    var factory = new DefaultCollateFactory();
    assertNull("unknown name must return null, not a default fallback",
        factory.getCollate("not-a-collate"));
  }

  // ---------------------------------------------------------------------------
  // SPI plumbing — ServiceLoader picks up DefaultCollateFactory
  // ---------------------------------------------------------------------------

  @Test
  public void serviceLoaderDiscoversDefaultCollateFactoryViaMetaInfServices() {
    // The META-INF/services entry under
    // resources/META-INF/services/com.jetbrains.youtrackdb.internal.core.collate.CollateFactory
    // must list DefaultCollateFactory; pinning the SPI roundtrip catches a future
    // accidental rename or deletion of either the file or the class.
    var loader = ServiceLoader.load(CollateFactory.class);
    var found = false;
    for (CollateFactory factory : loader) {
      if (factory instanceof DefaultCollateFactory) {
        found = true;
        // Sanity: the discovered instance reports the same pair of names.
        assertNotNull(factory.getCollate("default"));
        assertNotNull(factory.getCollate("ci"));
        break;
      }
    }
    assertTrue("DefaultCollateFactory must be discoverable via ServiceLoader", found);
  }

  // ---------------------------------------------------------------------------
  // Built-in collates — getName, transform, toString surface
  // ---------------------------------------------------------------------------

  @Test
  public void defaultCollateNameConstantIsTheLiteralDefault() {
    // Persisted into existing schemas; pin the literal so a refactor that
    // changes the constant flips loudly.
    assertEquals("default", DefaultCollate.NAME);
    assertEquals("default", new DefaultCollate().getName());
  }

  @Test
  public void defaultCollateTransformReturnsTheInputUnchanged() {
    var collate = new DefaultCollate();
    assertNull(collate.transform(null));
    assertEquals("hello", collate.transform("hello"));
    var marker = new Object();
    // Reference equality — the default collate does not allocate.
    assertEquals(marker, collate.transform(marker));
  }

  @Test
  public void defaultCollateToStringMentionsTheClassNameAndTheNameConstant() {
    var s = new DefaultCollate().toString();
    assertTrue("toString must mention the simple class name", s.contains("DefaultCollate"));
    assertTrue("toString must mention the NAME constant", s.contains("default"));
  }

  @Test
  public void caseInsensitiveCollateNameConstantIsTheLiteralCi() {
    assertEquals("ci", CaseInsensitiveCollate.NAME);
    assertEquals("ci", new CaseInsensitiveCollate().getName());
  }

  @Test
  public void caseInsensitiveCollateTransformLowercasesAStringInputUsingEnglishLocale() {
    var collate = new CaseInsensitiveCollate();
    assertEquals("hello", collate.transform("HELLO"));
    assertEquals("hello", collate.transform("Hello"));
    assertEquals("hello world", collate.transform("Hello World"));
  }

  @Test
  public void caseInsensitiveCollateTransformLeavesNonStringNonCollectionInputsUnchanged() {
    var collate = new CaseInsensitiveCollate();
    var marker = new Object();
    assertEquals(marker, collate.transform(marker));
    // null short-circuits at the very top of the chain.
    assertNull(collate.transform(null));
    assertEquals(42, collate.transform(42));
  }

  @Test
  public void caseInsensitiveCollateToStringMentionsTheClassNameAndTheCiConstant() {
    var s = new CaseInsensitiveCollate().toString();
    assertTrue(s.contains("CaseInsensitiveCollate"));
    assertTrue(s.contains("ci"));
  }

  // ---------------------------------------------------------------------------
  // CaseInsensitiveCollate — compareForOrderBy fallback to case-sensitive
  // ---------------------------------------------------------------------------

  @Test
  public void compareForOrderByReturnsZeroOnIdenticalStrings() {
    var collate = new CaseInsensitiveCollate();
    assertEquals(0, collate.compareForOrderBy("foo", "foo"));
  }

  @Test
  public void compareForOrderByOrdersCaseInsensitivelyAndFallsBackToCaseSensitiveOnTie() {
    // "Foo" and "foo" tie under the case-insensitive prefix, so the method
    // falls back to the parent (case-sensitive) compare to disambiguate the
    // ordering. ASCII-wise 'F' (70) < 'f' (102), so "Foo" sorts before "foo".
    var collate = new CaseInsensitiveCollate();
    assertTrue("'Foo' must sort before 'foo' under the case-sensitive fallback",
        collate.compareForOrderBy("Foo", "foo") < 0);
    assertTrue("'foo' must sort after 'Foo'",
        collate.compareForOrderBy("foo", "Foo") > 0);
  }

  @Test
  public void compareForOrderByOrdersCaseInsensitiveDistinctStrings() {
    var collate = new CaseInsensitiveCollate();
    assertTrue("'apple' < 'BANANA' case-insensitively",
        collate.compareForOrderBy("apple", "BANANA") < 0);
    assertTrue("'BANANA' > 'apple' case-insensitively",
        collate.compareForOrderBy("BANANA", "apple") > 0);
  }

  // ---------------------------------------------------------------------------
  // CollateFactory contract — interface API surface (sanity smoke)
  // ---------------------------------------------------------------------------

  @Test
  public void collateFactoryInterfaceIsImplementedByDefaultFactory() {
    // Surface-level pin: the interface contract must be met by the default
    // implementation. A regression that drops the interface declaration would
    // fail at compile time; this test is a runtime smoke.
    CollateFactory factory = new DefaultCollateFactory();
    Collate collate = factory.getCollate("default");
    assertTrue(collate instanceof DefaultCollate);
  }
}
