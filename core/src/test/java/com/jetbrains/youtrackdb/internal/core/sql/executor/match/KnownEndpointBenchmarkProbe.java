package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** Test-only scoped observation. Real session identity and record binding remain unchanged. */
public final class KnownEndpointBenchmarkProbe {

  private KnownEndpointBenchmarkProbe() {
  }

  public static final class Work {
    long loads;
    long sourceLoads;
    long positions;
    long rejectedPositions;
    long edgeEntries;
    long preflight;
    long edgeLoads;
    long transactionVisits;
    int nativeDecisionDepth;
  }

  private record State(Work work, Set<Integer> sourceCollections, Set<Integer> edgeCollections,
      long sourceEstimate) {
  }

  private static final ThreadLocal<State> STATE = new ThreadLocal<>();
  private static boolean installed;

  static synchronized void install() {
    if (installed) {
      return;
    }
    new AgentBuilder.Default().disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .type(named("com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded"))
        .transform((builder, type, loader, module, domain) -> builder
            .visit(Advice.to(ReadAdvice.class).on(named("executeReadRecord")
                .and(takesArguments(3))))
            .visit(Advice.to(EstimateAdvice.class).on(named("approximateCountClass")
                .and(takesArguments(String.class, boolean.class)))))
        .type(named("com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl"))
        .transform((builder, type, loader, module, domain) -> builder
            .visit(Advice.to(TransactionAdvice.class).on(named("getRecordOperationsInternal"))))
        .type(named(
            "com.jetbrains.youtrackdb.internal.core.sql.executor.match.NativeKnownEndpointStep"))
        .transform((builder, type, loader, module, domain) -> builder
            .visit(Advice.to(NativeDecisionAdvice.class).on(named("attemptSource"))))
        .type(named("com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag"))
        .transform((builder, type, loader, module, domain) -> builder
            .visit(Advice.to(EdgeAdvice.class).on(named("iterator").and(takesArguments(0)))))
        .type(named("com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass"))
        .transform((builder, type, loader, module, domain) -> builder
            .visit(Advice.to(PreflightAdvice.class).on(named("next").and(not(isBridge())))))
        .type(named("com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage"))
        .transform((builder, type, loader, module, domain) -> builder
            .visit(Advice.to(CountAdvice.class).on(named("count")
                .and(takesArguments(4)))))
        .installOn(ByteBuddyAgent.install());
    installed = true;
  }

  static AutoCloseable scope(Work work, Set<Integer> collections, Set<Integer> edges,
      long estimate) {
    var previous = STATE.get();
    STATE.set(new State(work, Set.copyOf(collections), Set.copyOf(edges), estimate));
    return () -> {
      if (previous == null) {
        STATE.remove();
      } else {
        STATE.set(previous);
      }
    };
  }

  public static void read(RecordIdInternal rid) {
    var state = STATE.get();
    if (state != null && state.work != null) {
      state.work.loads++;
      if (state.sourceCollections.contains(rid.getCollectionId())) {
        state.work.sourceLoads++;
      }
      if (state.edgeCollections.contains(rid.getCollectionId())) {
        state.work.edgeLoads++;
      }
    }
  }

  public static void nativeDecision(boolean entering) {
    var state = STATE.get();
    if (state != null && state.work != null) {
      state.work.nativeDecisionDepth += entering ? 1 : -1;
    }
  }

  public static Collection<RecordOperation> operations(Collection<RecordOperation> original) {
    var state = STATE.get();
    if (state == null || state.work == null || state.work.nativeDecisionDepth == 0) {
      return original;
    }
    // Count actual next() calls, not the write-set size or calls that only inspect size().
    // Restrict observation to native preparation, excluding retained query consumption.
    return new AbstractCollection<>() {
      @Override
      public int size() {
        return original.size();
      }

      @Override
      public Iterator<RecordOperation> iterator() {
        var iterator = original.iterator();
        return new Iterator<>() {
          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public RecordOperation next() {
            var operation = iterator.next();
            state.work.transactionVisits++;
            return operation;
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    };
  }

  public static long estimate(String name, boolean polymorphic, long actual) {
    var state = STATE.get();
    return state != null && state.sourceEstimate >= 0 && polymorphic
        && name.equals("BenchSource") ? state.sourceEstimate : actual;
  }

  public static void preflight(EntityImpl record) {
    var state = STATE.get();
    if (state != null && state.work != null
        && state.sourceCollections.contains(record.getIdentity().getCollectionId())) {
      state.work.preflight++;
    }
  }

  public static Runnable count(Runnable original) {
    var state = STATE.get();
    if (state == null || state.work == null || original == null) {
      return original;
    }
    return () -> {
      try {
        original.run();
        state.work.positions++;
      } catch (RuntimeException failure) {
        // Storage can retry an optimistic read under a lock. A repeated rejected charge does
        // not visit another position, so count rejected attempts separately from spent work.
        state.work.rejectedPositions++;
        throw failure;
      }
    };
  }

  public static Iterator<RidPair> edges(Iterator<RidPair> original) {
    var state = STATE.get();
    if (state == null || state.work == null) {
      return original;
    }
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return original.hasNext();
      }

      @Override
      public RidPair next() {
        state.work.edgeEntries++;
        return original.next();
      }

      @Override
      public void remove() {
        original.remove();
      }
    };
  }

  public static class NativeDecisionAdvice {
    @Advice.OnMethodEnter
    public static void enter() {
      KnownEndpointBenchmarkProbe.nativeDecision(true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit() {
      KnownEndpointBenchmarkProbe.nativeDecision(false);
    }
  }

  public static class TransactionAdvice {
    @Advice.OnMethodExit
    public static void exit(
        @Advice.Return(readOnly = false) Collection<RecordOperation> operations) {
      operations = KnownEndpointBenchmarkProbe.operations(operations);
    }
  }

  public static class EdgeAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.Return(readOnly = false) Iterator<RidPair> iterator) {
      iterator = KnownEndpointBenchmarkProbe.edges(iterator);
    }
  }

  public static class ReadAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) RecordIdInternal rid) {
      KnownEndpointBenchmarkProbe.read(rid);
    }
  }

  public static class EstimateAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.Argument(0) String name, @Advice.Argument(1) boolean poly,
        @Advice.Return(readOnly = false) long result) {
      result = KnownEndpointBenchmarkProbe.estimate(name, poly, result);
    }
  }

  public static class PreflightAdvice {
    @Advice.OnMethodExit
    public static void exit(@Advice.Return EntityImpl record) {
      KnownEndpointBenchmarkProbe.preflight(record);
    }
  }

  public static class CountAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(value = 3, readOnly = false) Runnable hook) {
      hook = KnownEndpointBenchmarkProbe.count(hook);
    }
  }
}
