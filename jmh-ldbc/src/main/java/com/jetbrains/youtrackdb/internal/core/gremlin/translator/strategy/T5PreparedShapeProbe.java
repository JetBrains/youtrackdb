package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.ConnectiveP;

/** Benchmark-only declaration capture immediately before root MATCH selection, never execution. */
public final class T5PreparedShapeProbe
    extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
    implements TraversalStrategy.ProviderOptimizationStrategy {

  /** A closed, immutable tree. Scalar leaves retain their exact runtime type. */
  public record Identity(String kind, List<Object> parts) {
    public Identity {
      parts = List.copyOf(parts);
    }
  }

  public record Declaration(List<Identity> steps, List<Integer> completedUnits) {
    public Declaration {
      steps = List.copyOf(steps);
      completedUnits = List.copyOf(completedUnits);
    }

    /** Completion is dispatch history, not proof of a safe prefix or an executable cut. */
    public void requireCut(Declaration actual, int cut) {
      if (!equals(actual)) {
        throw new IllegalArgumentException("Prepared shape or recogniser boundaries drifted");
      }
      if (cut < 1 || cut >= steps.size() || !completedUnits.contains(cut)) {
        throw new IllegalArgumentException("Lost boundary or cut inside recogniser unit");
      }
    }
  }

  private final Consumer<Declaration> capture;

  public T5PreparedShapeProbe(Consumer<Declaration> capture) {
    this.capture = capture;
  }

  @Override
  public Set<Class<? extends ProviderOptimizationStrategy>> applyPost() {
    return Set.of(GremlinToMatchStrategy.class);
  }

  @Override
  public void apply(Traversal.Admin<?, ?> traversal) {
    if (!traversal.isRoot()) {
      return;
    }
    var steps = preparedSteps(traversal);
    var units = new ArrayList<Integer>();
    GremlinStepWalker.production().walkObserved(traversal, units::add);
    capture.accept(new Declaration(steps, units));
  }

  private static List<Identity> preparedSteps(Traversal.Admin<?, ?> traversal) {
    // Bytecode retains constructor operands. Prepared structure also records strategy rewrites
    // and current HasContainer values, which can differ from their construction-time operands.
    Object operands = snapshot(traversal.getBytecode(), new IdentityHashMap<>());
    var result = new ArrayList<Identity>();
    for (var step : traversal.getSteps()) {
      var parts = new ArrayList<Object>();
      parts.add(snapshot(step.getLabels(), new IdentityHashMap<>()));
      parts.add(operands);
      if (step instanceof HasContainerHolder<?, ?> holder) {
        for (var has : holder.getHasContainers()) {
          parts.add(new Identity("has", List.of(has.getKey(),
              snapshot(has.getPredicate(), new IdentityHashMap<>()))));
        }
      }
      if (step instanceof TraversalParent parent) {
        for (var child : parent.getLocalChildren()) {
          parts.add(new Identity("local", new ArrayList<>(preparedSteps(child))));
        }
        for (var child : parent.getGlobalChildren()) {
          parts.add(new Identity("global", new ArrayList<>(preparedSteps(child))));
        }
      }
      // Render only after typed operands have passed cycle and support checks.
      parts.add(step.toString());
      result.add(new Identity(step.getClass().getName(), parts));
    }
    return List.copyOf(result);
  }

  private static Object snapshot(Object value, IdentityHashMap<Object, Boolean> active) {
    if (value == null) {
      return new Identity("null", List.of());
    }
    Class<?> type = value.getClass();
    if (value instanceof String || value instanceof Boolean || value instanceof Character
        || type == Byte.class || type == Short.class || type == Integer.class
        || type == Long.class || type == Float.class || type == Double.class
        || type == java.math.BigInteger.class || type == java.math.BigDecimal.class) {
      return value;
    }
    if (type == Date.class) {
      return new Identity("date-millis", List.of(((Date) value).getTime()));
    }
    if (value instanceof Enum<?> e) {
      return new Identity(type.getName(), List.of(e.name()));
    }
    if (active.put(value, Boolean.TRUE) != null) {
      throw new IllegalArgumentException("Cyclic prepared identity");
    }
    try {
      var parts = new ArrayList<Object>();
      String kind = type.getName();
      if (value instanceof Bytecode code) {
        for (var instruction : code.getStepInstructions()) {
          parts.add(new Identity(instruction.getOperator(),
              List.of(snapshot(instruction.getArguments(), active))));
        }
      } else if (value instanceof P<?> predicate) {
        if (predicate instanceof ConnectiveP<?> connective) {
          for (var child : connective.getPredicates()) {
            parts.add(snapshot(child, active));
          }
        } else {
          parts.add(snapshot(predicate.getBiPredicate(), active));
          parts.add(snapshot(predicate.getValue(), active));
        }
      } else if (value instanceof Collection<?> values) {
        kind = value instanceof Set<?> ? "set" : "list";
        for (var item : values) {
          parts.add(snapshot(item, active));
        }
      } else if (value instanceof Map<?, ?> values) {
        kind = "map";
        for (var entry : values.entrySet()) {
          parts.add(new Identity("entry",
              List.of(snapshot(entry.getKey(), active), snapshot(entry.getValue(), active))));
        }
      } else if (type.isArray()) {
        for (int i = 0; i < Array.getLength(value); i++) {
          parts.add(snapshot(Array.get(value, i), active));
        }
      } else {
        throw new IllegalArgumentException("Unsupported prepared identity: " + type.getName());
      }
      return new Identity(kind, parts);
    } finally {
      active.remove(value);
    }
  }
}
