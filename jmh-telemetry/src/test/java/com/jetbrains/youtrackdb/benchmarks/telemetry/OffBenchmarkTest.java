package com.jetbrains.youtrackdb.benchmarks.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy.FailingTranslator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.junit.Test;
import org.openjdk.jmh.infra.Blackhole;

public class OffBenchmarkTest {

  private final OffBenchmark benchmark = new OffBenchmark();

  private static Blackhole blackhole() {
    return new Blackhole(
        "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
  }

  private static void setup(OffBenchmark.Fixture fixture, int rows) throws Exception {
    fixture.rows = rows;
    fixture.telemetryScenario = "off";
    fixture.setup();
  }

  /// Both sizes execute the actual entry points, then validate their consumed plans after timing.
  @Test
  public void allPathsReturnTheDeclaredCountRepeatedly() throws Exception {
    for (int rows : new int[] {1, 10000}) {
      var fixture = new OffBenchmark.Fixture();
      setup(fixture, rows);
      try {
        for (int repeat = 0; repeat < 2; repeat++) {
          assertEquals(rows, benchmark.sql(blackhole(), fixture));
        }
        var check = new OffBenchmark.PathCheck();
        for (boolean translated : new boolean[] {true, false}) {
          check.start(fixture, translated);
          for (int repeat = 0; repeat < 2; repeat++) {
            assertEquals(rows, translated
                ? benchmark.translated(blackhole(), check)
                : benchmark.fallback(blackhole(), check));
          }
          check.verify();
          assertEquals(false, fixture.isTransactionOpen());
        }
      } finally {
        fixture.close();
      }
    }
  }

  /// Misrouting either real entry point must fail its iteration check outside measurement.
  @Test
  public void misroutedEntryPointsFailOutsideTheMeasuredCall() throws Exception {
    var misroute = new AtomicBoolean();
    var fixture = new OffBenchmark.Fixture() {
      @Override
      GraphTraversal<?, ?> traversal(boolean translated) {
        return super.traversal(misroute.get() ? !translated : translated);
      }
    };
    setup(fixture, 1);
    try {
      var check = new OffBenchmark.PathCheck();
      misroute.set(true);
      check.start(fixture, true);
      assertEquals(1, benchmark.translated(blackhole(), check));
      assertThrows(IllegalStateException.class, check::verify);
      check.start(fixture, false);
      assertEquals(1, benchmark.fallback(blackhole(), check));
      assertThrows(IllegalStateException.class, check::verify);
      // A later correct fallback must not hide an earlier accidental translation.
      misroute.set(false);
      assertEquals(1, benchmark.fallback(blackhole(), check));
      fixture.verifyExecutedPath(false);
      assertThrows(IllegalStateException.class, check::verify);
    } finally {
      fixture.close();
    }
  }

  /// A translator that passes preflight then throws must not produce a successful native score.
  @Test
  public void translationFailureAfterFirstCallFailsIterationCheck() throws Exception {
    var calls = new AtomicInteger();
    var strategy = FailingTranslator.afterFirstCall(calls);
    var fixture = new OffBenchmark.Fixture() {
      @Override
      GraphTraversal<?, ?> traversal(boolean translated) {
        var traversal = super.traversal(translated);
        if (translated) {
          var strategies = traversal.asAdmin().getStrategies().clone();
          strategies.addStrategies(strategy);
          traversal.asAdmin().setStrategies(strategies);
        }
        return traversal;
      }
    };
    setup(fixture, 1);
    try {
      assertEquals(1, calls.get());
      var check = new OffBenchmark.PathCheck();
      check.start(fixture, true);
      assertEquals(1, benchmark.translated(blackhole(), check));
      assertEquals(2, calls.get());
      assertThrows(IllegalStateException.class, check::verify);
      assertEquals(false, fixture.isTransactionOpen());
    } finally {
      fixture.close();
    }
  }

  /// An intermediate decline or swallowed failure cannot hide behind a successful last call.
  @Test
  public void transientTranslationProblemsFailEvenAfterRecovery() throws Exception {
    for (boolean throwsFailure : new boolean[] {false, true}) {
      var calls = new AtomicInteger();
      var strategy = FailingTranslator.onSecondCall(calls, throwsFailure);
      var fixture = new OffBenchmark.Fixture() {
        @Override
        GraphTraversal<?, ?> traversal(boolean translated) {
          var traversal = super.traversal(translated);
          if (translated) {
            var strategies = traversal.asAdmin().getStrategies().clone();
            strategies.addStrategies(strategy);
            traversal.asAdmin().setStrategies(strategies);
          }
          return traversal;
        }
      };
      setup(fixture, 1);
      try {
        var check = new OffBenchmark.PathCheck();
        check.start(fixture, true);
        assertEquals(1, benchmark.translated(blackhole(), check));
        assertEquals(1, benchmark.translated(blackhole(), check));
        assertEquals(3, calls.get());
        fixture.verifyExecutedPath(true);
        assertThrows(IllegalStateException.class, check::verify);
        // A fresh iteration snapshots current counters, not lifetime preflight outcomes.
        check.start(fixture, true);
        assertEquals(1, benchmark.translated(blackhole(), check));
        check.verify();
      } finally {
        fixture.close();
      }
    }
  }

  /// An empty Gremlin iteration cannot validate a leftover preflight traversal.
  @Test
  public void iterationWithoutAnExecutedTraversalFails() throws Exception {
    var fixture = new OffBenchmark.Fixture();
    setup(fixture, 1);
    try {
      var check = new OffBenchmark.PathCheck();
      check.start(fixture, true);
      assertThrows(IllegalStateException.class, check::verify);
    } finally {
      fixture.close();
    }
  }

  /// SQL requests no path state and all lifecycle hooks avoid per-invocation JMH timing.
  @Test
  public void pathFixturesAreIterationOnlyAndGremlinOnly() throws Exception {
    assertEquals(List.of(Blackhole.class, OffBenchmark.Fixture.class),
        List.of(OffBenchmark.class.getMethod("sql", Blackhole.class,
            OffBenchmark.Fixture.class).getParameterTypes()));
    for (var type : List.of(OffBenchmark.class, OffBenchmark.Fixture.class,
        OffBenchmark.PathCheck.class)) {
      for (var method : type.getDeclaredMethods()) {
        var setup = method.getAnnotation(org.openjdk.jmh.annotations.Setup.class);
        var teardown = method.getAnnotation(org.openjdk.jmh.annotations.TearDown.class);
        if (setup != null) {
          assertEquals(false, setup.value() == org.openjdk.jmh.annotations.Level.Invocation);
        }
        if (teardown != null) {
          assertEquals(false, teardown.value() == org.openjdk.jmh.annotations.Level.Invocation);
        }
      }
    }
  }

  /// An enabled effective cache is rejected without changing the caller's global setting.
  @Test
  public void enabledGlobalCacheFailsBeforeOpeningDatabase() {
    var setting = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED;
    var previous = setting.getValue();
    try {
      setting.setValue(true);
      var fixture = new OffBenchmark.Fixture();
      fixture.telemetryScenario = "off";
      assertThrows(IllegalStateException.class, fixture::setup);
      assertEquals(true, setting.getValueAsBoolean());
    } finally {
      setting.setValue(previous);
    }
  }

  /// An on arm is unavailable, rather than silently measured as off.
  @Test
  public void undeclaredScenarioFailsBeforeOpeningDatabase() {
    var fixture = new OffBenchmark.Fixture();
    fixture.telemetryScenario = "on";
    assertThrows(IllegalArgumentException.class, fixture::setup);
  }

  /// Missing or extra boundaries and a wrong preflight count reject the fixture.
  @Test
  public void wrongPathsAndCountsFailInsteadOfSubstitutingWork() {
    OffBenchmark.requirePath(true, 1);
    OffBenchmark.requirePath(false, 0);
    assertThrows(IllegalStateException.class, () -> OffBenchmark.requirePath(true, 0));
    assertThrows(IllegalStateException.class, () -> OffBenchmark.requirePath(true, 2));
    assertThrows(IllegalStateException.class, () -> OffBenchmark.requirePath(false, 1));
    OffBenchmark.requireCount(1, 1);
    assertThrows(IllegalStateException.class, () -> OffBenchmark.requireCount(1, 0));
  }

  /// Consumption reaches the final row and closes before returning the measured result.
  @Test
  public void completeConsumptionClosesBeforeReturn() throws Exception {
    var iterator = List.of(1, 2, 3).iterator();
    var closed = new AtomicBoolean();
    assertEquals(3, OffBenchmark.consume(iterator, () -> closed.set(true), blackhole()));
    assertEquals(false, iterator.hasNext());
    assertEquals(true, closed.get());
  }

  /// Every measured wrapper propagates both read and close failures, closes, and rolls back.
  @Test
  public void measuredWrappersCloseAndEndTransactionsOnFailure() throws Exception {
    for (boolean failOnClose : new boolean[] {false, true}) {
      var inject = new AtomicBoolean();
      var closed = new AtomicBoolean();
      var failure = new IllegalStateException("injected resource failure");
      var fixture = new OffBenchmark.Fixture() {
        @Override
        Cursor sqlResult() {
          var result = super.sqlResult();
          if (!inject.get()) {
            return result;
          }
          Iterator<Object> iterator = new Iterator<>() {
            public boolean hasNext() {
              return result.iterator().hasNext();
            }

            public Object next() {
              if (!failOnClose) {
                throw failure;
              }
              return result.iterator().next();
            }
          };
          return new Cursor(iterator, () -> {
            result.resource().close();
            closed.set(true);
            if (failOnClose) {
              throw failure;
            }
          });
        }

        @Override
        GraphTraversal<?, ?> traversal(boolean translated) {
          var traversal = super.traversal(translated);
          if (!inject.get()) {
            return traversal;
          }
          return (GraphTraversal<?, ?>) Proxy.newProxyInstance(
              GraphTraversal.class.getClassLoader(), new Class<?>[] {GraphTraversal.class},
              (proxy, method, args) -> {
                if (method.getName().equals("next") && !failOnClose) {
                  throw failure;
                }
                try {
                  var value = method.invoke(traversal, args);
                  if (method.getName().equals("close")) {
                    closed.set(true);
                    if (failOnClose) {
                      throw failure;
                    }
                  }
                  return value;
                } catch (InvocationTargetException exception) {
                  throw exception.getCause();
                }
              });
        }
      };
      setup(fixture, 1);
      try {
        var check = new OffBenchmark.PathCheck();
        check.start(fixture, true);
        inject.set(true);
        for (int arm = 0; arm < 3; arm++) {
          closed.set(false);
          int selected = arm;
          assertEquals(failure, assertThrows(IllegalStateException.class, () -> {
            switch (selected) {
              case 0 -> benchmark.sql(blackhole(), fixture);
              case 1 -> benchmark.translated(blackhole(), check);
              default -> benchmark.fallback(blackhole(), check);
            }
          }));
          assertEquals(true, closed.get());
          assertEquals(false, fixture.isTransactionOpen());
          if (arm != 0) {
            fixture.verifyExecutedPath(arm == 1);
          }
        }
      } finally {
        fixture.close();
      }
    }
  }
}
