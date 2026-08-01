package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;

/**
 * Drive helpers shared by the two boundary-step test classes, which exercise the same lifecycle
 * through {@link YTDBMatchPlanStep} and {@link MultiPlanMatchStep}. A helper duplicated per class
 * has to be hardened twice, and the class pair already carries one such split (the raw-entity reader
 * exists under two names with two bodies), so anything both classes drive belongs here.
 */
final class BoundaryStepTestSupport {

  /**
   * Pull budget for {@link #drainPayloads}. High enough that no legitimate fixture reaches it — the
   * boundary tests emit a handful of rows — and low enough to fail fast.
   */
  private static final int MAX_PULLS = 1_000;

  private BoundaryStepTestSupport() {
  }

  /**
   * Drives the step to exhaustion and returns every emitted payload in order.
   *
   * <p>Exhaustion means the {@link FastNoSuchElementException} the step throws once its shaped
   * payloads run dry, and nothing else. A plain {@link java.util.NoSuchElementException} reaching
   * this helper is a failed pass, not an ended one: the step's terminal iteration-failure branch
   * closes the stream and the plan and rethrows whatever the pass threw, and both classes back
   * their streams with sources that raise exactly that type when pulled dry. Catching the supertype
   * would report such a pass as a clean drain, and the tests that assert only on Mockito
   * interactions would stay green through it.
   *
   * <p>The pull budget turns the other silent failure into a loud one. A step that stops signalling
   * exhaustion — the never-exhausting stream stubs several tests in both classes install would do
   * it — would otherwise spin here until the surefire fork hits the job's wall-clock timeout, and a
   * timed-out job names no failing test.
   */
  static List<Object> drainPayloads(AbstractMatchPlanStep<Object, ?> step) {
    var payloads = new ArrayList<>();
    for (int pull = 0; pull < MAX_PULLS; pull++) {
      Traverser.Admin<?> traverser;
      try {
        traverser = step.processNextStart();
      } catch (FastNoSuchElementException exhausted) {
        return payloads;
      }
      // Read the payload as Object: the element path binds E to Vertex, so reading a SCALAR count
      // cell through the typed get() would insert a cast the aggregate payload cannot satisfy.
      payloads.add(traverser.get());
    }
    throw new AssertionError(
        "the boundary step emitted " + MAX_PULLS + " payloads without signalling exhaustion; its"
            + " stream never reports hasNext == false");
  }
}
