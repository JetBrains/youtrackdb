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
package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Node;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLFunctionCall;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SimpleNode;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Decides whether a parsed statement reads any value that is not a pure function of storage state and
 * the bound parameters. Such a query must never be cached: a second {@code query()} call replaying a
 * cached result would return the value the first call computed, not the value a fresh execution would
 * compute now.
 *
 * <p>Two sources of non-determinism are detected:
 *
 * <ul>
 *   <li><b>Denylisted functions.</b> A small, build-time-stable set of builtin functions whose result
 *       varies per call: {@code sysdate()}, the zero-argument form of {@code date()} (which returns
 *       "now"; {@code date('2020-01-01')} with an argument is deterministic and stays cacheable),
 *       {@code uuid()}, {@code eval(...)} (evaluates an arbitrary expression string that may itself be
 *       non-deterministic), and the reflective {@code math_random()}.
 *   <li><b>Per-row context variables.</b> Any identifier whose name begins with {@code $} ({@code
 *       $current}, {@code $parent}, {@code $matched}, {@code $depth}, and so on). These bind to
 *       executor-maintained per-row state the cache's delta builder does not reproduce, so a query
 *       referencing one is bypassed conservatively rather than reconciled.
 * </ul>
 *
 * <p>An explicit {@code NOCACHE} hint on a SELECT statement ({@link SQLSelectStatement#getNoCache()}
 * {@code == TRUE}) is the user-facing opt-out for cases the denylist cannot see — chiefly a custom
 * user-defined function the caller knows is non-deterministic. It is treated as non-deterministic
 * here so the caller's hint short-circuits caching.
 *
 * <p><b>Fail-open by design.</b> The denylist enumerates the known non-deterministic functions; a new
 * non-deterministic builtin added without a denylist entry would slip through and be cached. The
 * enumeration-completeness guard that asserts every registered function is classified lands with the
 * aggregate work in a later track; the {@code NOCACHE} hint is the escape valve until then.
 *
 * <p><b>Traversal.</b> The walk descends the full JJTree node tree from the statement root, so it
 * reaches every expression position — top-level WHERE, ORDER BY items and their modifier chains,
 * projection / RETURN expressions, and arbitrarily nested sub-expressions. A single non-deterministic
 * reference anywhere forces the bypass.
 */
public final class NonDeterministicQueryDetector {

  /**
   * Builtin function names whose result varies per invocation. Compared case-insensitively against
   * the raw function name via {@code equalsIgnoreCase}. {@code date} is handled specially (only the
   * zero-argument form is non-deterministic) and is therefore NOT in this set; see {@link
   * #isNonDeterministicFunction}.
   */
  private static final Set<String> NON_DETERMINISTIC_FUNCTIONS =
      Set.of("sysdate", "uuid", "eval", "math_random");

  private NonDeterministicQueryDetector() {
  }

  /**
   * Returns {@code true} when the statement must bypass the cache: it carries a {@code NOCACHE} hint,
   * calls a denylisted non-deterministic function, or references a per-row context variable anywhere
   * in its AST.
   */
  public static boolean containsNonDeterministicReference(@Nonnull SQLStatement statement) {
    // Explicit per-query opt-out. Only SELECT carries the NOCACHE token in the grammar.
    if (statement instanceof SQLSelectStatement select
        && Boolean.TRUE.equals(select.getNoCache())) {
      return true;
    }
    return walk(statement);
  }

  /**
   * Depth-first walk over the JJTree node tree. Returns {@code true} as soon as any node is a
   * denylisted function call or a context-variable identifier; short-circuits the rest of the tree.
   */
  private static boolean walk(@Nonnull Node node) {
    if (node instanceof SQLFunctionCall call && isNonDeterministicFunction(call)) {
      return true;
    }
    if (node instanceof SQLIdentifier id && isContextVariable(id)) {
      return true;
    }
    var childCount = node.jjtGetNumChildren();
    for (var i = 0; i < childCount; i++) {
      var child = node.jjtGetChild(i);
      // Every parser node is a SimpleNode (and thus a Node); guard defensively in case a future
      // grammar attaches a non-SimpleNode child so the cast cannot throw at runtime.
      if (child instanceof SimpleNode && walk(child)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A function call is non-deterministic when its (lowercased) name is on the denylist, or it is the
   * zero-argument {@code date()} form (which returns the current instant). {@code date('...')} with an
   * argument parses a fixed literal and is deterministic.
   */
  private static boolean isNonDeterministicFunction(@Nonnull SQLFunctionCall call) {
    var name = call.getName();
    if (name == null) {
      return false;
    }
    var raw = name.getStringValue();
    if (raw == null) {
      return false;
    }
    // Compare case-insensitively against the small static denylist without allocating a lowercased
    // String per function-call node (this runs for every call node in the full-AST walk). The
    // denylist is tiny, so a handful of equalsIgnoreCase checks are cheaper than a toLowerCase copy.
    for (var fn : NON_DETERMINISTIC_FUNCTIONS) {
      if (fn.equalsIgnoreCase(raw)) {
        return true;
      }
    }
    // Zero-arg date() is "now"; date(<arg>) is a fixed parse of the literal and stays cacheable.
    return "date".equalsIgnoreCase(raw) && call.getParams().isEmpty();
  }

  /**
   * A context-variable reference is an identifier whose raw value begins with {@code $} ({@code
   * $current}, {@code $parent}, ...). {@link SQLIdentifier#getValue()} returns the value verbatim, so
   * the {@code $} prefix survives. The grammar folds the leading {@code $} into the identifier token,
   * so a context variable parses as a single identifier rather than a separate operator node.
   */
  private static boolean isContextVariable(@Nonnull SQLIdentifier id) {
    var value = id.getValue();
    return value != null && value.startsWith("$");
  }
}
