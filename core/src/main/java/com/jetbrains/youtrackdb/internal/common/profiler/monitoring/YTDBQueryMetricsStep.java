package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import com.jetbrains.youtrackdb.internal.common.profiler.Ticker;
import com.jetbrains.youtrackdb.internal.common.profiler.monitoring.QueryMetricsListener.QueryDetails;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.Arrays;
import java.util.Iterator;
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

  /// Extends [GroovyTranslator.DefaultTypeTranslator] to preserve labels and property names in the
  /// query string while parameterizing actual values. For example, `g.V().has("age", 29)` becomes
  /// `g.V().has("age", _args_0)`.
  static class ValueAnonymizingTypeTranslator
      extends GroovyTranslator.DefaultTypeTranslator {

    /// Operators whose ALL arguments are structural (labels or property keys).
    private static final Set<String> STRUCTURAL_OPERATORS = Set.of(
        "hasLabel", "hasKey", "hasNot",
        "out", "in", "both", "outE", "inE", "bothE",
        "addV", "addE",
        "values", "valueMap", "elementMap", "properties", "propertyMap",
        "project", "select", "as", "by", "from", "to",
        "aggregate", "cap", "subgraph"
    );

    ValueAnonymizingTypeTranslator() {
      super(true);
    }

    @Override
    protected Script produceScript(String traversalSource, Bytecode bytecode) {
      script.append(traversalSource);
      for (final var instruction : bytecode.getInstructions()) {
        final var op = instruction.getOperator();
        final var args = instruction.getArguments();

        if (GraphTraversalSource.Symbols.tx.equals(op)) {
          // special case: tx().commit() / tx().rollback() — rewrite parentheses
          script.append(".").append(op).append("().").append(args[0].toString()).append("(");
        } else {
          script.append(".").append(op).append("(");
        }

        if (args.length > 0) {
          if (GraphTraversalSource.Symbols.tx.equals(op)) {
            // args already handled above
          } else if (GraphTraversal.Symbols.inject.equals(op)) {
            appendInjectArgs(args);
          } else if (STRUCTURAL_OPERATORS.contains(op)) {
            appendAllStructural(args);
          } else if (GraphTraversal.Symbols.has.equals(op)
              || GraphTraversal.Symbols.property.equals(op)) {
            if (args.length >= 2) {
              appendHasArgs(args);
            } else {
              appendAllStructural(args);
            }
          } else {
            appendAllParameterized(args);
          }
        }
        script.append(")");
      }
      return script;
    }

    private void appendAllStructural(Object[] args) {
      final Iterator<Object> it = Arrays.stream(args).iterator();
      while (it.hasNext()) {
        appendStructural(it.next());
        if (it.hasNext()) {
          script.append(",");
        }
      }
    }

    /// inject(null, null) needs `(Object)` prefix to avoid Groovy guessing the
    /// JDK collection extension form.
    private void appendInjectArgs(Object[] args) {
      final Iterator<Object> it = Arrays.stream(args).iterator();
      while (it.hasNext()) {
        final Object o = it.next();
        if (o == null) {
          script.append("(Object)");
        }
        convertToScript(o);
        if (it.hasNext()) {
          script.append(",");
        }
      }
    }

    private void appendAllParameterized(Object[] args) {
      final Iterator<Object> it = Arrays.stream(args).iterator();
      while (it.hasNext()) {
        convertToScript(it.next());
        if (it.hasNext()) {
          script.append(",");
        }
      }
    }

    /// For `has(key, value)` preserve key, parameterize value.
    /// For `has(label, key, value)` preserve label and key, parameterize value.
    private void appendHasArgs(Object[] args) {
      for (int i = 0; i < args.length; i++) {
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

    private void appendStructural(Object arg) {
      if (arg instanceof String s) {
        script.append(getSyntax(s));
      } else {
        convertToScript(arg);
      }
    }
  }
}
