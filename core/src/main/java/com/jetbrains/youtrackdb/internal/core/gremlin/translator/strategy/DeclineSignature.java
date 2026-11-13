package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/**
 * Immutable diagnostic text in segments. Names come from Class, not traversal values.
 * Equality and hashing use the exact rendered characters, including separators. No traversal or
 * step survives recording. Text is joined only when an operator requests the frequency report.
 */
final class DeclineSignature {

  private final String[] segments;
  private final int hash;

  private DeclineSignature(String[] segments) {
    this.segments = segments;
    int result = 0;
    for (var segment : segments) {
      int power = 1;
      for (int i = 0; i < segment.length(); i++) {
        power *= 31;
      }
      result = result * power + segment.hashCode();
    }
    hash = result;
  }

  static DeclineSignature text(String text) {
    return new DeclineSignature(new String[] {text});
  }

  static DeclineSignature traversal(Traversal.Admin<?, ?> traversal) {
    var steps = traversal.getSteps();
    var segments = new String[steps.size() * 2 + 1];
    segments[0] = "[";
    for (int i = 0; i < steps.size(); i++) {
      segments[2 * i + 1] = steps.get(i).getClass().getSimpleName();
      segments[2 * i + 2] = i + 1 == steps.size() ? "]" : ", ";
    }
    if (steps.isEmpty()) {
      segments[0] = "[]";
    }
    return new DeclineSignature(segments);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof DeclineSignature that) || hash != that.hash) {
      return false;
    }
    // Segment boundaries are not identity. The String API and traversal API share one map.
    int leftSegment = 0;
    int rightSegment = 0;
    int leftOffset = 0;
    int rightOffset = 0;
    while (true) {
      while (leftSegment < segments.length
          && leftOffset == segments[leftSegment].length()) {
        leftSegment++;
        leftOffset = 0;
      }
      while (rightSegment < that.segments.length
          && rightOffset == that.segments[rightSegment].length()) {
        rightSegment++;
        rightOffset = 0;
      }
      if (leftSegment == segments.length || rightSegment == that.segments.length) {
        return leftSegment == segments.length && rightSegment == that.segments.length;
      }
      // Cached class names and separators usually share identity on repeated declines.
      if (leftOffset == 0 && rightOffset == 0
          && segments[leftSegment] == that.segments[rightSegment]) {
        leftSegment++;
        rightSegment++;
        continue;
      }
      if (segments[leftSegment].charAt(leftOffset++)
          != that.segments[rightSegment].charAt(rightOffset++)) {
        return false;
      }
    }
  }

  @Override
  public String toString() {
    return String.join("", segments);
  }
}
