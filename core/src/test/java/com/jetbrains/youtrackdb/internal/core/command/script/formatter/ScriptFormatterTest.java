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
package com.jetbrains.youtrackdb.internal.core.command.script.formatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Before;
import org.junit.Test;

/**
 * Behavioral tests for the four {@link ScriptFormatter} implementations —
 * {@link JSScriptFormatter}, {@link GroovyScriptFormatter}, {@link RubyScriptFormatter},
 * {@link SQLScriptFormatter}. Tests pin the exact generated text (not length or substring) so a
 * change to the template is a deliberate, visible event.
 *
 * <p>The {@code session} argument to each {@link ScriptFormatter} method is unused in all four
 * implementations (verified against the method bodies at commit time — JS / Groovy / Ruby /
 * SQL all ignore the parameter). Tests pass {@code null} to make the convention explicit.
 *
 * <p>Extends {@link TestUtilsFixture} solely because {@link Function#Function(
 * com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)} calls
 * {@code session.newEntity("OFunction")}, which requires an active transaction and a registered
 * schema class. No formatter under test touches the session or the database; the transaction is
 * rolled back in {@link TestUtilsFixture#rollbackIfLeftOpen}.
 *
 * <p>Tests live under {@code formatter/} (sibling of the production package) so they run as
 * part of parallel {@code core} test execution. A non-parameterized single-class layout is
 * used instead of {@code @RunWith(Parameterized.class)} because the four formatters have
 * incompatible semantics (JS/Groovy template-based, Ruby tab-wrapped with Scanner, SQL null-
 * definition / code-only invoke); encoding per-formatter expected text as parameterized data
 * produces noisy test reports where Ruby-specific branches fail with "wrong formatter" errors.
 * Four method-group clusters (js*, groovy*, ruby*, sql*) give clearer test names and diff.
 */
public class ScriptFormatterTest extends TestUtilsFixture {

  private Function function;

  @Before
  public void buildFunction() {
    // session.newEntity("OFunction") requires an active transaction; the rollback guard in
    // TestUtilsFixture.rollbackIfLeftOpen (superclass @After) keeps the fixture clean.
    // We never call f.save(session), so nothing touches disk.
    session.begin();
    function = new Function(session);
  }

  // ---------------------------------------------------------------------------
  // JSScriptFormatter
  // ---------------------------------------------------------------------------

  /** JS definition with zero parameters: empty parens, code between braces. */
  @Test
  public void jsGetFunctionDefinitionWithZeroParams() {
    function.setName("foo").setParameters(Collections.emptyList());
    function.setCode("return 42");

    assertEquals(
        "function foo() {\nreturn 42\n}\n",
        new JSScriptFormatter().getFunctionDefinition(null, function));
  }

  /** JS definition with a single parameter: name inside parens, code between braces. */
  @Test
  public void jsGetFunctionDefinitionWithOneParam() {
    function.setName("bar").setParameters(List.of("x"));
    function.setCode("return x");

    assertEquals(
        "function bar(x) {\nreturn x\n}\n",
        new JSScriptFormatter().getFunctionDefinition(null, function));
  }

  /** JS definition with multiple parameters: comma-separated, no spaces. */
  @Test
  public void jsGetFunctionDefinitionWithMultipleParams() {
    function.setName("sum").setParameters(List.of("a", "b", "c"));
    function.setCode("return a + b + c");

    assertEquals(
        "function sum(a,b,c) {\nreturn a + b + c\n}\n",
        new JSScriptFormatter().getFunctionDefinition(null, function));
  }

  /** JS definition with null parameters list: empty parens (guarded branch). */
  @Test
  public void jsGetFunctionDefinitionWithNullParamsList() {
    function.setName("nofn").setParameters(null);
    function.setCode("return");

    assertEquals(
        "function nofn() {\nreturn\n}\n",
        new JSScriptFormatter().getFunctionDefinition(null, function));
  }

  /** JS invoke with null args: empty parens, trailing semicolon. */
  @Test
  public void jsGetFunctionInvokeWithNullArgs() {
    function.setName("ping");
    assertEquals("ping();", new JSScriptFormatter().getFunctionInvoke(null, function, null));
  }

  /** JS invoke with empty args array: empty parens, trailing semicolon. */
  @Test
  public void jsGetFunctionInvokeWithEmptyArgs() {
    function.setName("ping");
    assertEquals("ping();",
        new JSScriptFormatter().getFunctionInvoke(null, function, new Object[0]));
  }

  /** JS invoke with scalar args: inline toString, comma-separated, trailing semicolon. */
  @Test
  public void jsGetFunctionInvokeWithScalarArgs() {
    function.setName("sum");
    assertEquals(
        "sum(1,2,3);",
        new JSScriptFormatter().getFunctionInvoke(null, function, new Object[] {1, 2, 3}));
  }

  /** JS invoke with mixed arg types (including null): each arg rendered via toString. */
  @Test
  public void jsGetFunctionInvokeWithMixedArgs() {
    function.setName("mix");
    assertEquals(
        "mix(hello,42,null);",
        new JSScriptFormatter()
            .getFunctionInvoke(null, function, new Object[] {"hello", 42, null}));
  }

  /**
   * TC7 iter-2 boundary pin: empty function name is not rejected by the formatter — it emits
   * {@code "function (x) {...}"} (an anonymous function declaration syntactically valid in JS
   * but semantically an anonymous expression). Pin the current pass-through contract so a
   * future empty-name guard is a deliberate, visible change.
   */
  @Test
  public void jsGetFunctionDefinitionWithEmptyNameEmitsAnonymousShape() {
    function.setName("").setParameters(List.of("x"));
    function.setCode("return x");
    assertEquals(
        "empty-name JS function definition currently emits `function (x) {...}` verbatim",
        "function (x) {\nreturn x\n}\n",
        new JSScriptFormatter().getFunctionDefinition(null, function));
  }

  /**
   * TC7 iter-2 boundary pin: {@link StringBuilder#append(String)} renders {@code null} as the
   * literal string {@code "null"}. Pin the current no-null-guard behavior — a regression that
   * accidentally NPEd on null-name would be caught, and a future explicit null-rejection would
   * be a deliberate change.
   */
  @Test
  public void jsGetFunctionDefinitionWithNullNameRendersLiteralNullString() {
    function.setName(null).setParameters(List.of());
    function.setCode("return");
    assertEquals(
        "null-name JS function renders `function null() {...}` (StringBuilder append-null)",
        "function null() {\nreturn\n}\n",
        new JSScriptFormatter().getFunctionDefinition(null, function));
  }

  /**
   * TC7 iter-2 boundary pin: parameter names with commas are NOT escaped — the formatter
   * concatenates them with a comma separator, so a single parameter named {@code "x,y"} emits
   * as two ostensible parameters. Pin this unsanitized pass-through so a future parameter
   * validation is a deliberate change.
   */
  @Test
  public void jsGetFunctionDefinitionWithCommaInParameterNameIsUnescaped() {
    function.setName("sum").setParameters(List.of("x,y"));
    function.setCode("return x+y");
    assertEquals(
        "comma-in-param name passes through the comma-separated formatter unchanged",
        "function sum(x,y) {\nreturn x+y\n}\n",
        new JSScriptFormatter().getFunctionDefinition(null, function));
  }

  // ---------------------------------------------------------------------------
  // GroovyScriptFormatter (same template as JS with "def" instead of "function")
  // ---------------------------------------------------------------------------

  /** Groovy definition with zero parameters: "def" keyword, empty parens, braces. */
  @Test
  public void groovyGetFunctionDefinitionWithZeroParams() {
    function.setName("foo").setParameters(Collections.emptyList());
    function.setCode("return 42");

    assertEquals(
        "def foo() {\nreturn 42\n}\n",
        new GroovyScriptFormatter().getFunctionDefinition(null, function));
  }

  /** Groovy definition with a single parameter. */
  @Test
  public void groovyGetFunctionDefinitionWithOneParam() {
    function.setName("bar").setParameters(List.of("x"));
    function.setCode("return x");

    assertEquals(
        "def bar(x) {\nreturn x\n}\n",
        new GroovyScriptFormatter().getFunctionDefinition(null, function));
  }

  /** Groovy definition with multiple parameters. */
  @Test
  public void groovyGetFunctionDefinitionWithMultipleParams() {
    function.setName("sum").setParameters(List.of("a", "b"));
    function.setCode("return a + b");

    assertEquals(
        "def sum(a,b) {\nreturn a + b\n}\n",
        new GroovyScriptFormatter().getFunctionDefinition(null, function));
  }

  /** Groovy definition with null parameters list (guarded branch). */
  @Test
  public void groovyGetFunctionDefinitionWithNullParamsList() {
    function.setName("nofn").setParameters(null);
    function.setCode("");

    assertEquals(
        "def nofn() {\n\n}\n", new GroovyScriptFormatter().getFunctionDefinition(null, function));
  }

  /** Groovy invoke with null args. */
  @Test
  public void groovyGetFunctionInvokeWithNullArgs() {
    function.setName("ping");
    assertEquals("ping();", new GroovyScriptFormatter().getFunctionInvoke(null, function, null));
  }

  /** Groovy invoke with array args: comma-separated. */
  @Test
  public void groovyGetFunctionInvokeWithArrayArgs() {
    function.setName("sum");
    assertEquals(
        "sum(1,2);",
        new GroovyScriptFormatter().getFunctionInvoke(null, function, new Object[] {1, 2}));
  }

  // ---------------------------------------------------------------------------
  // RubyScriptFormatter (tab-prefixed lines via Scanner)
  //
  // IMPORTANT LATENT BUG in RubyScriptFormatter.getFunctionDefinition:
  //   scanner.useDelimiter("\n").skip("\r");
  // The .skip("\r") call THROWS NoSuchElementException if the code does not start with a
  // carriage return. This means the current production code only works for code bodies that
  // begin with "\r" (Windows line endings or hand-crafted input). For any other code, the
  // formatter crashes with NoSuchElementException, which the caller must catch.
  //
  // Tests below pin:
  //  (1) The happy path with a "\r"-prefixed body (current observable shape).
  //  (2) The NoSuchElementException for a body WITHOUT a "\r" prefix — a WHEN-FIXED pin so
  //      Track 22's {@code skip("\r")}-removal (or {@code if hasNext("\r")} guard) is a
  //      deliberate visible change. Without this pin, a behavior flip would go unnoticed by
  //      tests.
  //  (3) The invoke path, which has no Scanner-related bug — same template as JS/Groovy.
  // ---------------------------------------------------------------------------

  /**
   * Ruby definition with a "\r"-prefixed single-line body takes the happy path (skip("\r")
   * consumes the \r, then Scanner delimits on "\n" and prepends "\t" to each line).
   * Pins the observed shape including the leading \r and tab-wrapped body line.
   */
  @Test
  public void rubyGetFunctionDefinitionWithCarriageReturnPrefixedCodeHappyPath() {
    function.setName("bar").setParameters(List.of("x"));
    function.setCode("\rreturn x");

    assertEquals(
        "def bar(x)\n\treturn x\nend\n",
        new RubyScriptFormatter().getFunctionDefinition(null, function));
  }

  /**
   * Ruby definition with a "\r"-prefixed multi-line body: Scanner splits each "\n"-separated
   * token and prepends "\t" to each. This is a faithful pin of the current (surprising)
   * behavior — consecutive source lines are joined by the tab only (no inter-line newline).
   * WHEN-FIXED: Track 22 — RubyScriptFormatter likely should emit a trailing newline per line
   * so each body line occupies its own line. Until then, this test locks the observed shape.
   */
  @Test
  public void rubyGetFunctionDefinitionWithMultiLineCodeJoinsLinesByTab() {
    function.setName("baz").setParameters(List.of("a", "b"));
    function.setCode("\rline1\nline2\nline3");

    assertEquals(
        "def baz(a,b)\n\tline1\tline2\tline3\nend\n",
        new RubyScriptFormatter().getFunctionDefinition(null, function));
  }

  /**
   * Ruby definition with a code body that does NOT start with "\r" throws {@link
   * NoSuchElementException} at the {@code scanner.skip("\r")} call. Pins this latent
   * production bug so Track 22's hardening (either remove skip or guard with hasNext("\r"))
   * is a visible change — this test will start to PASS where it currently asserts-throws,
   * forcing a conscious re-pin.
   *
   * <p>WHEN-FIXED: Track 22 — replace unconditional skip("\r") with a conditional guard OR
   * delete the class if Ruby support is removed.
   */
  @Test
  public void rubyGetFunctionDefinitionWithoutCarriageReturnPrefixThrows() {
    function.setName("bar").setParameters(List.of("x"));
    function.setCode("return x");

    assertThrows(
        "Ruby formatter without leading \\r must throw at scanner.skip(\"\\r\")",
        NoSuchElementException.class,
        () -> new RubyScriptFormatter().getFunctionDefinition(null, function));
  }

  /**
   * Ruby definition with EMPTY code: scanner.skip("\r") throws at position 0 because there
   * is no "\r" to consume. Pins the observed crash for empty input. WHEN-FIXED: Track 22.
   */
  @Test
  public void rubyGetFunctionDefinitionWithEmptyCodeThrows() {
    function.setName("foo").setParameters(Collections.emptyList());
    function.setCode("");

    assertThrows(
        NoSuchElementException.class,
        () -> new RubyScriptFormatter().getFunctionDefinition(null, function));
  }

  /** Ruby invoke follows the same template as JS/Groovy — no scanner, no skip bug. */
  @Test
  public void rubyGetFunctionInvokeWithNullArgs() {
    function.setName("ping");
    assertEquals("ping();", new RubyScriptFormatter().getFunctionInvoke(null, function, null));
  }

  /** Ruby invoke with multiple args. */
  @Test
  public void rubyGetFunctionInvokeWithMultipleArgs() {
    function.setName("sum");
    assertEquals(
        "sum(1,2,3);",
        new RubyScriptFormatter().getFunctionInvoke(null, function, new Object[] {1, 2, 3}));
  }

  // ---------------------------------------------------------------------------
  // SQLScriptFormatter (null-definition, code-only invoke)
  // ---------------------------------------------------------------------------

  /** SQL definition is always null regardless of function state (no wrapping). */
  @Test
  public void sqlGetFunctionDefinitionAlwaysReturnsNull() {
    function.setName("foo").setParameters(List.of("x", "y"));
    function.setCode("SELECT :x + :y");

    assertNull(new SQLScriptFormatter().getFunctionDefinition(null, function));
  }

  /** SQL definition still null for null-params branch (no crash). */
  @Test
  public void sqlGetFunctionDefinitionNullParamsReturnsNull() {
    function.setName("foo").setParameters(null);
    function.setCode("SELECT 1");

    assertNull(new SQLScriptFormatter().getFunctionDefinition(null, function));
  }

  /** SQL invoke returns the code verbatim regardless of args (args TODO'd in prod comment). */
  @Test
  public void sqlGetFunctionInvokeReturnsCodeVerbatim() {
    function.setName("ignored").setParameters(List.of("x"));
    function.setCode("SELECT :x");

    assertEquals(
        "SELECT :x",
        new SQLScriptFormatter().getFunctionInvoke(null, function, new Object[] {42}));
  }

  /** SQL invoke returns code even when args is null. */
  @Test
  public void sqlGetFunctionInvokeWithNullArgsReturnsCode() {
    function.setName("ignored");
    function.setCode("SELECT 'ok'");

    assertEquals("SELECT 'ok'", new SQLScriptFormatter().getFunctionInvoke(null, function, null));
  }

  /**
   * SQL invoke returns code even when it contains placeholders that would be replaced in other
   * formatters — SQL relies on downstream binding instead of template expansion. Pins the
   * current "pass-through" behavior.
   */
  @Test
  public void sqlGetFunctionInvokeDoesNotSubstitutePlaceholders() {
    function.setName("ignored").setParameters(List.of("a"));
    function.setCode("SELECT :a FROM V");

    assertEquals(
        "SELECT :a FROM V",
        new SQLScriptFormatter()
            .getFunctionInvoke(null, function, new Object[] {Arrays.asList(1, 2, 3)}));
  }
}
