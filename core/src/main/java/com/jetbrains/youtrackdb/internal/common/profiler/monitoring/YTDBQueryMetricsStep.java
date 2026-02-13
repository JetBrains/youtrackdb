package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener.QueryDetails;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Script;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.translator.GroovyTranslator;

public class YTDBQueryMetricsStep<S> extends AbstractStep<S, S> implements AutoCloseable {

  /// GroovyTranslator is not thread-safe: its internal TypeTranslator reuses a mutable Script
  /// object across calls. ThreadLocal gives one instance per thread to avoid both repeated
  /// allocation and concurrent corruption.
  private static final ThreadLocal<GroovyTranslator> GROOVY_TRANSLATOR =
      ThreadLocal.withInitial(() -> GroovyTranslator.of("g",
          new ValueAnonymizingTypeTranslator()));

  private final YTDBTransaction ytdbTx;
  private final String querySummary;
  private final Ticker ticker;
  private final boolean isLightweight;
  private boolean hasStarted = false;
  private long startMillis;
  private long nano;
  private long endNano;

  public YTDBQueryMetricsStep(
      Traversal.Admin<?, ?> traversal,
      YTDBTransaction ytdbTx,
      @Nullable String querySummary,
      Ticker ticker
  ) {
    super(traversal);
    this.ytdbTx = ytdbTx;
    this.querySummary = querySummary;
    this.isLightweight = ytdbTx.getQueryMonitoringMode() == QueryMonitoringMode.LIGHTWEIGHT;
    this.ticker = ticker;
  }

  @Override
  protected Admin<S> processNextStart() throws NoSuchElementException {
    queryHasStarted();
    return starts.next();
  }

  @Override
  public boolean hasNext() {
    queryHasStarted();
    if (isLightweight) {
      try {
        return super.hasNext();
      } finally {
        endNano = ticker.approximateNanoTime();
      }
    } else {
      final var now = System.nanoTime();
      try {
        return super.hasNext();
      } finally {
        nano += System.nanoTime() - now;
      }
    }
  }

  @Override
  public Admin<S> next() {
    queryHasStarted();
    if (isLightweight) {
      try {
        return super.next();
      } finally {
        endNano = ticker.approximateNanoTime();
      }
    } else {
      final var now = System.nanoTime();
      try {
        return super.next();
      } finally {
        nano += System.nanoTime() - now;
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (!hasStarted) {
      return;
    }

    final var duration = isLightweight ? endNano - nano : nano;
    ytdbTx.getQueryMetricsListener().queryFinished(
        new QueryDetails() {
          @Override
          public String getQuery() {
            return GROOVY_TRANSLATOR.get().translate(traversal.getBytecode()).getScript();
          }

          @Override
          public String getQuerySummary() {
            return querySummary;
          }

          @Override
          public String getTransactionTrackingId() {
            return ytdbTx.getTrackingId();
          }
        },
        startMillis,
        duration
    );
  }

  private void queryHasStarted() {
    if (hasStarted) {
      return;
    }
    hasStarted = true;

    if (isLightweight) {
      this.startMillis = ticker.approximateCurrentTimeMillis();
      this.nano = ticker.approximateNanoTime();
    } else {
      this.startMillis = System.currentTimeMillis();
    }
  }

  /// Extends [GroovyTranslator.DefaultTypeTranslator] to produce a readable Gremlin query string
  /// that preserves structural identifiers (labels, property keys, step labels, side-effect keys)
  /// while replacing actual data values with bind variable placeholders (`_args_N`).
  ///
  /// This makes the query string safe for logging and monitoring without exposing user data,
  /// while keeping enough structure to understand which query was executed.
  ///
  /// The translator categorizes each Gremlin step's arguments into four groups:
  ///
  /// 1. **Fully structural**: ALL arguments are identifiers (labels, keys, etc.) and are
  ///    rendered as-is. This applies to a known set of operators such as `hasLabel`,
  ///    `out`, `values`, `as`, `aggregate`, etc. Examples: `hasLabel("person")`,
  ///    `out("knows")`, `values("name")`, `as("a")`, `aggregate("x")`.
  ///
  /// 2. **Mixed — `has`**: all arguments except the last are structural (property key,
  ///    optionally preceded by a label), and the last argument is a value or predicate
  ///    that gets parameterized. Examples:
  ///    - `has("age", 29)` → `has("age", _args_0)` — key preserved, value hidden
  ///    - `has("person", "age", 29)` → `has("person", "age", _args_0)` — label + key preserved
  ///    - `has("age")` → `has("age")` — single-arg existence check, key preserved
  ///
  /// 3. **Mixed — `property`**: the property key is structural; the value and any
  ///    meta-property key/value pairs are parameterized. When a Cardinality enum precedes
  ///    the key, it is also rendered structurally. Examples:
  ///    - `property("name", "foo")` → `property("name", _args_0)` — key preserved
  ///    - `property("name", "marko", "since", 2020)` → `property("name", _args_0, _args_1,
  ///      _args_2)` — key preserved, value and meta-properties parameterized
  ///    - `property(Cardinality.single, "name", "marko")` →
  ///      `property(Cardinality.single, "name", _args_0)` — cardinality and key preserved
  ///
  /// 4. **Fully parameterized** (everything else): all arguments are treated as values and
  ///    replaced with placeholders. Non-string/non-primitive arguments (traversals, enums, P
  ///    predicates) are recursed into via `convertToScript` which handles them correctly —
  ///    traversals are rendered structurally, enums as constants, and P values are parameterized.
  ///    Examples: `limit(_args_0)`, `is(P.gt(_args_0))`, `choose(__.hasLabel("person"), ...)`.
  static class ValueAnonymizingTypeTranslator
      extends GroovyTranslator.DefaultTypeTranslator {

    /// Operators whose ALL arguments are structural (labels, property keys, step labels,
    /// or side-effect keys). String arguments of these operators are rendered literally;
    /// non-string arguments (traversals, enums, etc.) are recursed into normally.
    private static final Set<String> STRUCTURAL_OPERATORS = Set.of(
        "hasLabel", "hasKey", "hasNot",
        "out", "in", "both", "outE", "inE", "bothE",
        "addV", "addE",
        "values", "valueMap", "elementMap", "properties", "propertyMap",
        "project", "select", "as", "by", "from", "to",
        "aggregate", "cap", "subgraph"
    );

    ValueAnonymizingTypeTranslator() {
      // withParameters=true makes convertToScript() parameterize primitives and strings
      // by default. We selectively bypass this for structural arguments by calling
      // appendStructural() instead of convertToScript().
      super(true);
    }

    @Override
    protected Script produceScript(String traversalSource, Bytecode bytecode) {
      script.append(traversalSource);
      for (final var instruction : bytecode.getInstructions()) {
        final var op = instruction.getOperator();
        final var args = instruction.getArguments();

        if (GraphTraversalSource.Symbols.tx.equals(op)) {
          // tx() is special in TinkerPop: the bytecode stores the command (commit/rollback)
          // as an argument, but the Groovy syntax is tx().commit() not tx("commit").
          return script.append(".").append(op).append("().").append(args[0].toString())
              .append("()");
        }

        script.append(".").append(op).append("(");

        if (args.length > 0) {
          if (STRUCTURAL_OPERATORS.contains(op)) {
            // All arguments are identifiers — render them literally.
            appendAllStructural(args);
          } else if (GraphTraversal.Symbols.has.equals(op)) {
            // has(): leading args are structural (key, or label + key), and the last
            // arg is a value/predicate. Special case: has(key) with a single arg is a
            // pure existence check — the key is structural, no value to parameterize.
            if (args.length >= 2) {
              appendHasArgs(args);
            } else {
              appendAllStructural(args);
            }
          } else if (GraphTraversal.Symbols.property.equals(op)) {
            // property(): the property key is structural; the value and any
            // meta-property key/value pairs are all parameterized. When a Cardinality
            // enum precedes the key, it is also rendered structurally.
            appendPropertyArgs(args);
          } else {
            // Default: all arguments are parameterized. Non-primitive arguments
            // (Bytecode, Traversal, P, Enum) are handled by convertToScript() which
            // recurses into them — e.g., a nested traversal like __.out("knows") will
            // re-enter produceScript() and have its own arguments categorized.
            appendAllParameterized(args);
          }
        }
        script.append(")");
      }
      return script;
    }

    /// Renders all arguments as structural identifiers — strings are quoted literally,
    /// non-strings are recursed into via [#convertToScript].
    private void appendAllStructural(Object[] args) {
      final var it = Arrays.stream(args).iterator();
      while (it.hasNext()) {
        appendStructural(it.next());
        if (it.hasNext()) {
          script.append(",");
        }
      }
    }

    /// Renders all arguments as parameterized values via [#convertToScript], which replaces
    /// primitives and strings with `_args_N` placeholders.
    private void appendAllParameterized(Object[] args) {
      final var it = Arrays.stream(args).iterator();
      while (it.hasNext()) {
        convertToScript(it.next());
        if (it.hasNext()) {
          script.append(",");
        }
      }
    }

    /// Renders arguments for the `has()` step, where all arguments except the last are
    /// structural (labels/keys) and the last is a value or predicate.
    ///
    /// Examples:
    /// - `has("age", 29)` — "age" is structural, 29 is parameterized
    /// - `has("person", "name", "marko")` — "person" and "name" structural, "marko" parameterized
    /// - `has("age", P.gt(27))` — "age" is structural, P.gt(27) is recursed into and 27
    ///   inside the predicate is parameterized by [#convertToScript]
    private void appendHasArgs(Object[] args) {
      for (var i = 0; i < args.length; i++) {
        if (i < args.length - 1) {
          appendStructural(args[i]);
        } else {
          convertToScript(args[i]);
        }
        if (i < args.length - 1) {
          script.append(",");
        }
      }
    }

    /// Renders arguments for the `property()` step. The property key is structural;
    /// the value and any meta-property key/value pairs are parameterized. When a
    /// Cardinality enum precedes the key, it is also rendered structurally.
    ///
    /// Examples:
    /// - `property("name", "foo")` — "name" is structural, "foo" is parameterized
    /// - `property("name", "marko", "since", 2020)` — "name" is structural, "marko",
    ///   "since", and 2020 are all parameterized
    /// - `property(Cardinality.single, "name", "marko")` — Cardinality and "name" are
    ///   structural, "marko" is parameterized
    private void appendPropertyArgs(Object[] args) {
      // When the first argument is not a String, it is a Cardinality enum and the
      // property key follows as the second argument.
      var keyIndex = (args[0] instanceof String) ? 0 : 1;
      for (var i = 0; i <= keyIndex; i++) {
        if (i > 0) {
          script.append(",");
        }
        appendStructural(args[i]);
      }
      for (var i = keyIndex + 1; i < args.length; i++) {
        script.append(",");
        convertToScript(args[i]);
      }
    }

    /// Renders a single argument as a structural identifier. Strings are quoted literally
    /// (bypassing parameterization). Non-strings (enums, traversals, predicates) are passed
    /// to [#convertToScript] which handles them according to their type.
    private void appendStructural(Object arg) {
      if (arg instanceof String s) {
        script.append(getSyntax(s));
      } else {
        convertToScript(arg);
      }
    }
  }
}
