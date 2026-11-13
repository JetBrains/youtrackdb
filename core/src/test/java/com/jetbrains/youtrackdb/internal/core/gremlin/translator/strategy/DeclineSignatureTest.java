package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.junit.Test;

public class DeclineSignatureTest {

  /** Deferred text equals the original builder for empty, root, child and unsupported shapes. */
  @Test
  public void signaturesEqualOriginalTextAndDoNotRetainSteps() {
    var traversals = List.of(__.identity().asAdmin(), __.V().has("x", 1).out("e").asAdmin(),
        __.E().asAdmin(), __.V().coalesce(__.out(), __.identity()).asAdmin(),
        __.start().asAdmin());
    for (var traversal : traversals) {
      var expected = baseStepShape(traversal);
      var signature = DeclineSignature.traversal(traversal);
      var text = DeclineSignature.text(expected);
      assertThat(signature.toString()).isEqualTo(expected);
      assertThat(signature).isEqualTo(text);
      assertThat(text).isEqualTo(signature);
      assertThat(signature.hashCode()).isEqualTo(expected.hashCode());
      assertThat(signature).isEqualTo(DeclineSignature.traversal(traversal));
      if (!traversal.getSteps().isEmpty()) {
        traversal.removeStep(0);
      }
      assertThat(signature.toString()).isEqualTo(expected);
    }
    assertThat(DeclineSignature.text("Aa")).isNotEqualTo(DeclineSignature.text("BB"));
    assertThat(DeclineSignature.text("a")).isNotEqualTo(DeclineSignature.text("b"));
    assertThat(DeclineSignature.text("a")).isNotEqualTo("a");
    assertThat(DeclineSignature.text("")).isEqualTo(DeclineSignature.text(""));
  }

  // Oracle copied from GremlinToMatchStrategy at the Track 3 base, 50d2944e2b.
  private static String baseStepShape(
      org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin<?, ?> traversal) {
    var shape = new StringBuilder("[");
    var steps = traversal.getSteps();
    for (int i = 0; i < steps.size(); i++) {
      if (i > 0) {
        shape.append(", ");
      }
      shape.append(steps.get(i).getClass().getSimpleName());
    }
    return shape.append("]").toString();
  }

  /** String and deferred callers share frequency identity and fill the same bounded map. */
  @Test
  public void deferredAndStringRecordingShareCapAndCounts() {
    var metrics = new GremlinTranslationMetrics(
        (com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry) null);
    var traversal = __.V().has("x", 1).asAdmin();
    metrics.recordDeclineTraversal(traversal);
    metrics.recordDecline("[GraphStep, HasStep]");
    for (int i = 1; i < GremlinTranslationMetrics.MAX_TRACKED_SHAPES; i++) {
      metrics.recordDecline("shape" + i);
    }
    metrics.recordDeclineTraversal(__.E().asAdmin());
    metrics.recordDeclineTraversal(traversal);
    assertThat(metrics.topDeclinedShapes(1)).containsExactly(
        new GremlinTranslationMetrics.ShapeCount("[GraphStep, HasStep]", 3));
    var expected = new java.util.HashSet<String>();
    expected.add("[GraphStep, HasStep]");
    for (int i = 1; i < GremlinTranslationMetrics.MAX_TRACKED_SHAPES; i++) {
      expected.add("shape" + i);
    }
    assertThat(metrics.topDeclinedShapes(Integer.MAX_VALUE))
        .extracting(GremlinTranslationMetrics.ShapeCount::shape)
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(metrics.getAttempts()).isEqualTo(259);
    assertThat(metrics.declineShare()).isEqualTo(1);
  }

  /** Labels preserve insertion order and null filtering. Empty sets share the immutable singleton. */
  @Test
  public void labelFilteringAndHarvestValuesPreserveOrder() {
    var traversal = __.V().asAdmin();
    var step = traversal.getStartStep();
    assertThat(GremlinStepLabels.userLabels(step)).isSameAs(java.util.Set.of());
    step.addLabel(null);
    assertThat(GremlinStepLabels.userLabels(step)).isEmpty();
    step.addLabel("second");
    step.addLabel("first");
    assertThat(GremlinStepLabels.userLabels(step)).containsExactly("second", "first");
    var encoder = new GremlinShapeEncoder(null);
    assertThat(encoder.paramSink().bindParam("second")).isNull();
    assertThat(encoder.paramSink().bindParam("first")).isNull();
    assertThat(encoder.bindings()).containsEntry(0, "second").containsEntry(1, "first");
  }
}
