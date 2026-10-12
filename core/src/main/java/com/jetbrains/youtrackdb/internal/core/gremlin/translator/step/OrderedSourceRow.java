package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.List;
import java.util.function.Supplier;

/** A sorted MATCH source and its live path identity, before the native select projection. */
public record OrderedSourceRow(Object source, List<Object> path,
    Supplier<Projection> projection) {

  public OrderedSourceRow {
    path = List.copyOf(path);
  }

  /** A nonproductive select still occupies a position in a source slice. */
  public record Projection(Object payload, boolean productive) {
  }
}
