package com.jetbrains.youtrackdb.internal.common.comparator;

import java.util.Comparator;
import org.apache.tinkerpop.gremlin.util.GremlinValueComparator;

/**
 * Compares values using TinkerPop orderability semantics.
 *
 * <p>The dependency comparator is deliberately used as the single implementation. This keeps type
 * priorities, numeric comparison, and recursive collection comparison aligned with the pinned
 * Gremlin version instead of maintaining a second interpretation of its bytecode.
 */
public final class GremlinOrderComparator implements Comparator<Object> {

  public static final GremlinOrderComparator INSTANCE = new GremlinOrderComparator();

  private GremlinOrderComparator() {
  }

  @Override
  public int compare(Object first, Object second) {
    return GremlinValueComparator.ORDERABILITY.compare(first, second);
  }
}
