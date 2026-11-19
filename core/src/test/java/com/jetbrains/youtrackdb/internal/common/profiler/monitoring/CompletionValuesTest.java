package com.jetbrains.youtrackdb.internal.common.profiler.monitoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.Test;

public class CompletionValuesTest {

  // A legacy implementation supplies only the original methods. New facts remain unknown.
  private static class LegacyDetails implements QueryMetricsListener.QueryDetails {
    @Override
    public String getQuery() {
      throw new AssertionError("A retained snapshot must not resolve query text");
    }

    @Override
    public String getQuerySummary() {
      return null;
    }

    @Override
    public String getTransactionTrackingId() {
      return null;
    }
  }

  @Test
  public void legacyDetailsHaveExplicitAbsenceWithoutInventingSuccessOrZero() {
    var details = new LegacyDetails();
    var value = QueryCompletion.capture(details);
    assertEquals(Optional.empty(), value.transactionTrackingId());
    assertEquals(Optional.empty(), value.explicitTrackingId());
    assertEquals(Optional.empty(), value.querySummary());
    assertEquals(Optional.empty(), value.operationName());
    assertEquals(Optional.empty(), value.collectionName());
    assertEquals(Optional.empty(), value.databaseName());
    assertEquals(Optional.empty(), value.outcome());
    assertEquals(OptionalLong.empty(), value.resultCount());
    assertEquals(OptionalLong.empty(), value.startedAtEpochNanos());
    assertEquals(OptionalLong.empty(), value.finishedAtEpochNanos());
    assertEquals(OptionalLong.empty(), value.slowQueryThresholdOverrideNanos());
    assertEquals(Optional.empty(), details.getErrorType());
    assertEquals(Optional.empty(), details.getParentContext());
    assertNull(details.getExecutionPlan());
    TransactionMetricsListener.TransactionDetails tx = () -> "42";
    assertEquals("42", tx.getTransactionTrackingId());
    assertEquals(Optional.empty(), tx.getDatabaseName());
    assertEquals(Optional.empty(), tx.getExplicitTrackingId());
    TransactionMetricsListener.NO_OP.writeTransactionCommitted(tx, 1, 2);
    TransactionMetricsListener.NO_OP.writeTransactionFailed(tx, 1, 2, new Exception());
    QueryMetricsListener.NO_OP.queryFinished(details, 1, 2);
  }

  @Test
  public void snapshotSurvivesViewInvalidationAndNeverReadsLiveObjects() {
    var live = new LegacyDetails() {
      boolean closed;

      @Override
      public com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan getExecutionPlan() {
        throw new AssertionError("A retained snapshot must not resolve a plan");
      }

      @Override
      public String getTransactionTrackingId() {
        if (closed) {
          throw new IllegalStateException();
        }
        return "entry-tx";
      }

      @Override
      public String getQuerySummary() {
        return "caller-tag";
      }

      @Override
      public Optional<String> getExplicitTrackingId() {
        return Optional.of("caller-id");
      }

      @Override
      public Optional<String> getOperationName() {
        return Optional.of("select");
      }

      @Override
      public Optional<String> getCollectionName() {
        return Optional.of("Item");
      }

      @Override
      public Optional<String> getDatabaseName() {
        return Optional.of("db");
      }

      @Override
      public Optional<CompletionOutcome> getOutcome() {
        return Optional.of(CompletionOutcome.succeeded());
      }

      @Override
      public OptionalLong getResultCount() {
        return OptionalLong.of(0);
      }

      @Override
      public OptionalLong getStartedAtEpochNanos() {
        return OptionalLong.of(100);
      }

      @Override
      public OptionalLong getFinishedAtEpochNanos() {
        return OptionalLong.of(500);
      }

      @Override
      public OptionalLong getSlowQueryThresholdOverrideNanos() {
        return OptionalLong.of(0);
      }

      @Override
      public Optional<Context> getParentContext() {
        throw new AssertionError("Context is callback-scoped");
      }
    };
    var value = QueryCompletion.capture(live);
    live.closed = true;
    assertEquals(Optional.of("entry-tx"), value.transactionTrackingId());
    assertEquals(Optional.of("caller-id"), value.explicitTrackingId());
    assertEquals(Optional.of("caller-tag"), value.querySummary());
    assertEquals(Optional.of("select"), value.operationName());
    assertEquals(Optional.of("Item"), value.collectionName());
    assertEquals(Optional.of("db"), value.databaseName());
    assertEquals(Optional.of(CompletionOutcome.succeeded()), value.outcome());
    assertEquals(OptionalLong.of(0), value.resultCount());
    assertEquals(OptionalLong.of(100), value.startedAtEpochNanos());
    assertEquals(OptionalLong.of(500), value.finishedAtEpochNanos());
    assertEquals(OptionalLong.of(0), value.slowQueryThresholdOverrideNanos());
  }

  @Test
  public void failureRetainsOnlyTypeThroughFactoriesAndOutcomeDerivedAccessor() {
    var failure = new IllegalArgumentException("sensitive-value", new Exception("sensitive-cause"));
    var outcome = CompletionOutcome.failed(failure);
    assertFalse(outcome.success());
    assertEquals(Optional.of(IllegalArgumentException.class.getName()), outcome.errorType());
    assertFalse(outcome.toString().contains("sensitive"));
    assertTrue(CompletionOutcome.succeeded().success());
    assertEquals(Optional.empty(), CompletionOutcome.succeeded().errorType());
    assertThrows(NullPointerException.class, () -> CompletionOutcome.failed(null));
    // No caller can inject a message or construct contradictory success and error values.
    assertTrue(Modifier.isFinal(CompletionOutcome.class.getModifiers()));
    for (var constructor : CompletionOutcome.class.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    var sameType = CompletionOutcome.failed(new IllegalArgumentException("different-message"));
    assertEquals(outcome, sameType);
    assertEquals(outcome.hashCode(), sameType.hashCode());
    assertEquals(CompletionOutcome.succeeded(), CompletionOutcome.succeeded());
    assertFalse(outcome.equals(null));
    assertFalse(outcome.equals("not an outcome"));
    assertFalse(outcome.equals(CompletionOutcome.succeeded()));
    assertFalse(outcome.equals(CompletionOutcome.failed(new IllegalStateException())));
    var details = new LegacyDetails() {
      @Override
      public Optional<CompletionOutcome> getOutcome() {
        return Optional.of(outcome);
      }
    };
    assertEquals(outcome.errorType(), details.getErrorType());
    var retained = QueryCompletion.capture(details);
    assertEquals(Optional.of(outcome), retained.outcome());
    assertEquals(details.getErrorType(), retained.outcome().flatMap(CompletionOutcome::errorType));
    // Check generic payloads too. Optional<Throwable> or Optional<Context> must not pass.
    var allowedTypes = Set.of("boolean", "java.util.OptionalLong",
        "java.util.Optional<java.lang.String>",
        "java.util.Optional<" + CompletionOutcome.class.getName() + ">");
    for (var type : new Class<?>[] {QueryCompletion.class, CompletionOutcome.class}) {
      for (var field : type.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers())) {
          assertTrue("Unexpected retained field: " + field,
              allowedTypes.contains(field.getGenericType().getTypeName()));
        }
      }
    }
  }

  @Test
  public void contextUsesHostStorageAndScopeRestoresPreviousValue() {
    var key = ContextKey.<String>named("completion-test");
    var previous = Context.current();
    var captured = previous.with(key, "entry");
    try (var ignored = captured.makeCurrent()) {
      assertEquals("entry", Context.current().get(key));
    }
    assertSame(previous, Context.current());
    assertEquals("entry", captured.get(key));
  }
}
