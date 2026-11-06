package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;

/** Benchmark-only declaration capture immediately before root MATCH selection, never execution. */
public final class T5PreparedShapeProbe
    extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
    implements TraversalStrategy.ProviderOptimizationStrategy {
  public record Declaration(List<String> steps, List<Integer> completedUnits) {
    public Declaration {
      steps = List.copyOf(steps);
      completedUnits = List.copyOf(completedUnits);
    }

    /** One-based root boundary. A syntactic step inside a folded unit is not a valid cut. */
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
    var units = new ArrayList<Integer>();
    var registry = new HashMap<Class<?>, StepRecogniser>();
    // Delegate the real recognisers and record only successful units in this root cursor.
    // Child walks share the registry, so their cursor positions must not become root cuts.
    GremlinStepWalker.PRODUCTION_RECOGNISERS
        .forEach((type, delegate) -> registry.put(type, new StepRecogniser() {
          @Override
          public Outcome recognize(StepCursor cursor, RecognitionContext context) {
            boolean root = cursor.peek() != null
                && cursor.peek().getTraversal() == traversal;
            var outcome = delegate.recognize(cursor, context);
            if (root && outcome == Outcome.ACCEPTED) {
              units.add(((StepStreamCursor) cursor).position());
            }
            return outcome;
          }

          @Override
          public boolean
              selectsPositionally(org.apache.tinkerpop.gremlin.process.traversal.Step<?, ?> step) {
            return delegate.selectsPositionally(step);
          }
        }));
    // No plan build, splice, flag override, cache publication or query open occurs here.
    new GremlinStepWalker(registry).walk(traversal);
    capture.accept(new Declaration(traversal.getSteps().stream()
        .map(s -> s.getClass().getName() + ":" + s).toList(), units));
  }
}
