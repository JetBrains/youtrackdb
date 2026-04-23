/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import javax.script.ScriptEngine;
import org.junit.Test;

/**
 * Behavioral tests for {@link SQLScriptEngineFactory}, the JSR-223 {@code ScriptEngineFactory}
 * that advertises YouTrackDB SQL as a scripting language.
 *
 * <p>The class is not discovered via {@code META-INF/services} (pinned as a dead-code gap in
 * {@code CommandScriptDeadCodeTest} — Step 1), so its only entry path is direct construction by
 * {@link SQLScriptEngine} which calls {@code new SQLScriptEngineFactory()} via reflection in
 * {@code SQLScriptEngine.getFactory()}. These tests pin every JSR-223 metadata accessor
 * individually so a change in the contract is visible at the test level.
 *
 * <p>Standalone (no database) — the factory is a pure metadata provider; its {@code
 * getScriptEngine()} constructs a {@link SQLScriptEngine} which is also DB-independent until
 * {@code eval} is called.
 */
public class SQLScriptEngineFactoryTest {

  // ===========================================================================
  // Identity metadata — engine name / language name / versions.
  // ===========================================================================

  /** getEngineName returns the constant SQLScriptEngine.NAME ("sql"). */
  @Test
  public void getEngineNameReturnsSqlConstant() {
    assertEquals("sql", new SQLScriptEngineFactory().getEngineName());
    assertEquals(
        "engine name must match the public SQLScriptEngine.NAME constant",
        SQLScriptEngine.NAME,
        new SQLScriptEngineFactory().getEngineName());
  }

  /** getEngineVersion returns the YouTrackDB build version. */
  @Test
  public void getEngineVersionReturnsYouTrackDbVersion() {
    final var version = new SQLScriptEngineFactory().getEngineVersion();
    assertNotNull("engine version must be non-null", version);
    assertEquals(
        "engine version must match YouTrackDBConstants.getVersion() verbatim",
        YouTrackDBConstants.getVersion(),
        version);
  }

  /** getLanguageName mirrors the engine name. */
  @Test
  public void getLanguageNameReturnsSqlConstant() {
    assertEquals("sql", new SQLScriptEngineFactory().getLanguageName());
  }

  /** getLanguageVersion mirrors the engine version. */
  @Test
  public void getLanguageVersionReturnsYouTrackDbVersion() {
    assertEquals(
        YouTrackDBConstants.getVersion(), new SQLScriptEngineFactory().getLanguageVersion());
  }

  // ===========================================================================
  // Names / extensions — static final lists shared across instances.
  // ===========================================================================

  /**
   * getNames returns a list containing exactly "sql". Pins the precise contents so a
   * secondary-name addition is a visible change.
   */
  @Test
  public void getNamesContainsOnlySql() {
    final var names = new SQLScriptEngineFactory().getNames();
    assertNotNull("names list must be non-null", names);
    assertEquals("names list must have exactly one entry", 1, names.size());
    assertEquals("sql", names.get(0));
  }

  /**
   * Two factory instances share the same static {@code NAMES} list by identity. Pins the
   * "class-level cache" contract — a fresh factory does not rebuild the list.
   */
  @Test
  public void getNamesIsSharedAcrossInstances() {
    final var a = new SQLScriptEngineFactory().getNames();
    final var b = new SQLScriptEngineFactory().getNames();
    assertSame(
        "getNames() must return the same static NAMES list for every factory instance", a, b);
  }

  /**
   * getExtensions returns a list containing exactly "sql" (reusing NAME as the extension).
   * Pins the contract so changing the .sql extension is a visible break.
   */
  @Test
  public void getExtensionsContainsOnlySql() {
    final var ext = new SQLScriptEngineFactory().getExtensions();
    assertNotNull(ext);
    assertEquals(1, ext.size());
    assertEquals("sql", ext.get(0));
  }

  /** Static extensions list is shared identity-equal across instances. */
  @Test
  public void getExtensionsIsSharedAcrossInstances() {
    assertSame(
        new SQLScriptEngineFactory().getExtensions(),
        new SQLScriptEngineFactory().getExtensions());
  }

  // ===========================================================================
  // Stubs — JSR-223 methods that are deliberately null-returning.
  // ===========================================================================

  /** getMimeTypes is intentionally null — SQL has no published MIME type. */
  @Test
  public void getMimeTypesReturnsNull() {
    assertNull(new SQLScriptEngineFactory().getMimeTypes());
  }

  /** getParameter(anyKey) returns null for every JSR-223 parameter key. */
  @Test
  public void getParameterReturnsNullForAnyKey() {
    final var f = new SQLScriptEngineFactory();
    assertNull(f.getParameter(ScriptEngine.NAME));
    assertNull(f.getParameter(ScriptEngine.LANGUAGE));
    assertNull(f.getParameter(ScriptEngine.LANGUAGE_VERSION));
    assertNull(f.getParameter(ScriptEngine.ENGINE));
    assertNull(f.getParameter("arbitrary-unknown-key"));
    assertNull("null key must be handled without throwing", f.getParameter(null));
  }

  /** getMethodCallSyntax returns null regardless of args — SQL has no method-call dialect. */
  @Test
  public void getMethodCallSyntaxReturnsNull() {
    assertNull(new SQLScriptEngineFactory().getMethodCallSyntax("obj", "method"));
    assertNull(new SQLScriptEngineFactory().getMethodCallSyntax("obj", "method", "a", "b"));
    assertNull(new SQLScriptEngineFactory().getMethodCallSyntax(null, null));
  }

  /** getOutputStatement returns null — SQL has no "print" equivalent at the engine level. */
  @Test
  public void getOutputStatementReturnsNull() {
    assertNull(new SQLScriptEngineFactory().getOutputStatement("hello"));
    assertNull(new SQLScriptEngineFactory().getOutputStatement(""));
    assertNull(new SQLScriptEngineFactory().getOutputStatement(null));
  }

  // ===========================================================================
  // getProgram — joins statements with ";\n".
  // ===========================================================================

  /** Empty varargs produces empty string. Pins the zero-statement contract. */
  @Test
  public void getProgramWithNoStatementsReturnsEmptyString() {
    assertEquals("", new SQLScriptEngineFactory().getProgram());
  }

  /** Single statement gets trailing ";\n". */
  @Test
  public void getProgramWithOneStatementAppendsSemicolonNewline() {
    assertEquals(
        "SELECT 1;\n", new SQLScriptEngineFactory().getProgram("SELECT 1"));
  }

  /**
   * Multiple statements join with ";\n" between each and a trailing ";\n" after the last.
   * Pins the exact format (no separator deduplication, no final trimming).
   */
  @Test
  public void getProgramWithMultipleStatementsJoinsEachWithSemicolonNewline() {
    assertEquals(
        "SELECT 1;\nSELECT 2;\nSELECT 3;\n",
        new SQLScriptEngineFactory().getProgram("SELECT 1", "SELECT 2", "SELECT 3"));
  }

  /**
   * A statement that already ends with a semicolon gets an ADDITIONAL ";\n" appended (no
   * normalization). Pins the naive-concat contract.
   */
  @Test
  public void getProgramDoesNotNormalizeExistingSemicolons() {
    assertEquals(
        "SELECT 1;;\n",
        new SQLScriptEngineFactory().getProgram("SELECT 1;"));
  }

  /** An empty-string statement still gets ";\n" appended (pins the concat-even-if-empty behavior). */
  @Test
  public void getProgramEmptyStringStatementStillAppendsTerminator() {
    assertEquals(";\n", new SQLScriptEngineFactory().getProgram(""));
  }

  // ===========================================================================
  // getScriptEngine — round-trip with the factory as the engine's factory.
  // ===========================================================================

  /**
   * getScriptEngine() constructs a new {@link SQLScriptEngine} and passes {@code this} as the
   * factory. Each call creates a NEW engine instance (no caching). Pins the per-call
   * freshness contract.
   */
  @Test
  public void getScriptEngineCreatesFreshEngineEachCall() {
    final var factory = new SQLScriptEngineFactory();
    final var engineA = factory.getScriptEngine();
    final var engineB = factory.getScriptEngine();

    assertNotNull(engineA);
    assertNotNull(engineB);
    assertNotSame("each getScriptEngine() call must produce a fresh engine instance", engineA,
        engineB);
    assertTrue(engineA instanceof SQLScriptEngine);
    assertTrue(engineB instanceof SQLScriptEngine);
  }

  /**
   * The engine's {@code getFactory()} returns the factory passed at construction — proving the
   * round-trip wiring. Pins the "back-reference" contract for JSR-223 clients that dispatch via
   * {@code engine.getFactory()} for metadata.
   */
  @Test
  public void getScriptEngineReturnsEngineWiredBackToThisFactory() {
    final var factory = new SQLScriptEngineFactory();
    final var engine = factory.getScriptEngine();
    assertSame(
        "engine.getFactory() must return THIS factory (round-trip ctor wiring)",
        factory,
        engine.getFactory());
  }

  /**
   * Engines produced by DIFFERENT factory instances have DIFFERENT factories. Pins
   * per-instance identity (rules out a hidden singleton engine).
   */
  @Test
  public void differentFactoriesProduceEnginesWithDifferentFactories() {
    final var factoryA = new SQLScriptEngineFactory();
    final var factoryB = new SQLScriptEngineFactory();
    assertNotSame(factoryA, factoryB);

    final var engineA = factoryA.getScriptEngine();
    final var engineB = factoryB.getScriptEngine();
    assertSame(factoryA, engineA.getFactory());
    assertSame(factoryB, engineB.getFactory());
    assertFalse(engineA.getFactory() == engineB.getFactory());
  }

}
