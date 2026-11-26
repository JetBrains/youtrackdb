package org.apache.tinkerpop.gremlin.process.traversal.step.map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ReducingBarrierStep;
import org.junit.Test;

/** Identity proofs do not execute functions and survive clones and legitimate group rebuilds. */
public class BuiltInFunctionsTest {

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void replacementOfEitherComponentInvalidatesProofWithoutCallingIt() {
    List<Supplier<GraphTraversal<?, ?>>> shapes = List.of(() -> __.count(), () -> __.sum(),
        () -> __.min(), () -> __.max(), () -> __.mean(), () -> __.group(),
        () -> __.groupCount(), () -> __.tree());
    var calls = new AtomicInteger();
    for (var shape : shapes) {
      for (boolean seed : List.of(false, true)) {
        var step = (ReducingBarrierStep) shape.get().asAdmin().getEndStep();
        assertTrue(step.hasBuiltInFunctions());
        assertTrue(step.clone().hasBuiltInFunctions());
        if (seed) {
          step.setSeedSupplier(() -> {
            calls.incrementAndGet();
            return null;
          });
        } else {
          step.setReducingBiOperator((a, b) -> {
            calls.incrementAndGet();
            return a;
          });
        }
        assertFalse(step.hasBuiltInFunctions());
        assertFalse(step.clone().hasBuiltInFunctions());
      }
    }
    assertTrue(calls.get() == 0);
  }

  /** A normal value modulator rebuilds the reducer but cannot certify a replaced seed. */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void groupRebuildOnlyCertifiesItsOwnReducer() {
    var traversal = __.group().by("name").by(__.count());
    var group = (GroupStep) traversal.asAdmin().getEndStep();
    assertTrue(group.hasBuiltInFunctions());
    group.resetBarrierFromValueTraversal();
    assertTrue(group.hasBuiltInFunctions());
    assertTrue(group.clone().hasBuiltInFunctions());
    group.setSeedSupplier(() -> null);
    group.resetBarrierFromValueTraversal();
    assertFalse(group.hasBuiltInFunctions());
  }

  /** Buffer evidence uses only the actual class, including after native buffer replacement. */
  @Test
  public void barrierBufferProofNeverCallsCustomBuffer() {
    var traversal = __.inject(1).asAdmin();
    var ordinary = new NoOpBarrierStep<Integer>(traversal, 2500);
    assertTrue(ordinary.hasBuiltInBuffer());
    assertTrue(ordinary.clone().hasBuiltInBuffer());
    var explicit = new NoOpBarrierStep<Integer>(traversal, 2500,
        new org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet<>());
    assertTrue(explicit.hasBuiltInBuffer());
    var custom = new NoOpBarrierStep<Integer>(traversal, 2500,
        new org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet<>() {
          @Override
          public boolean isEmpty() {
            throw new AssertionError("proof must not inspect buffer contents");
          }
        });
    assertFalse(custom.hasBuiltInBuffer());
    // Native clone semantics allocate a fresh default buffer rather than copying the custom one.
    assertTrue(custom.clone().hasBuiltInBuffer());
    assertFalse(new NoOpBarrierStep<Integer>(traversal, 2500, null).hasBuiltInBuffer());
  }

  /** Opening an iterator is not necessary to distinguish a replaced graph supplier. */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void graphSupplierProofSurvivesCloning() {
    var graph = (GraphStep) __.V().asAdmin().getStartStep();
    assertTrue(graph.hasBuiltInIteratorSupplier());
    assertTrue(((GraphStep) graph.clone()).hasBuiltInIteratorSupplier());
    graph.setIteratorSupplier(() -> {
      throw new AssertionError("proof must not open the iterator");
    });
    assertFalse(graph.hasBuiltInIteratorSupplier());
    assertFalse(((GraphStep) graph.clone()).hasBuiltInIteratorSupplier());
  }
}
