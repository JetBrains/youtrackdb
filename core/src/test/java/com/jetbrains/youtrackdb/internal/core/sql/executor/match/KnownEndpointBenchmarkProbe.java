package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
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
    long edgeEntries;
    long edgeLoads;
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
        .type(named("com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag"))
        .transform((builder, type, loader, module, domain) -> builder
            .visit(Advice.to(EdgeAdvice.class).on(named("iterator").and(takesArguments(0)))))
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

  public static long estimate(String name, boolean polymorphic, long actual) {
    var state = STATE.get();
    return state != null && state.sourceEstimate >= 0 && polymorphic
        && name.equals("BenchSource") ? state.sourceEstimate : actual;
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

}
