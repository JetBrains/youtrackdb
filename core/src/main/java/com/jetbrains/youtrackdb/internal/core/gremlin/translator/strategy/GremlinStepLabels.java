package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Step;

/**
 * Extracts Gremlin {@code as(label)} markers from a step's {@link Step#getLabels()} set. TinkerPop
 * attaches user labels to the step they follow ({@code g.V().as("v")} labels the {@code GraphStep},
 * {@code out().as("friend")} labels the {@code VertexStep}), not via a separate {@code AsStep}.
 */
final class GremlinStepLabels {

  private GremlinStepLabels() {
    // Static helper — no instances.
  }

  /**
   * Returns the distinct non-null user labels on {@code step}. Null entries (from {@code as((String)
   * null)}) are skipped — they carry no binding.
   */
  static Set<String> userLabels(Step<?, ?> step) {
    var labels = new LinkedHashSet<String>();
    for (String label : step.getLabels()) {
      if (label != null) {
        labels.add(label);
      }
    }
    return labels;
  }
}
