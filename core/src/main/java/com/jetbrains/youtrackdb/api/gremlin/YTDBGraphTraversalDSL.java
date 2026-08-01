package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl;
import com.jetbrains.youtrackdb.internal.annotations.gremlin.dsl.GremlinDsl.SkipAsAnonymousMethod;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBRemovePropertyService;
import java.util.HashSet;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

@SuppressWarnings("unused")
@GremlinDsl(traversalSource = "com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSourceDSL")
public interface YTDBGraphTraversalDSL<S, E> extends GraphTraversal.Admin<S, E> {

  /// Removes one or several {@link Property}s from an element.
  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> removeProperty(String key, String... otherKeys) {

    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("The provided name must not be null or blank");
    }
    final var allKeys = new HashSet<String>();
    allKeys.add(key);

    if (otherKeys != null) {
      for (var k : otherKeys) {
        if (k == null || k.isBlank()) {
          throw new IllegalArgumentException("The provided name must not be null or blank");
        }
        allKeys.add(k);
      }
    }

    return call(
        YTDBRemovePropertyService.NAME,
        Map.of(YTDBRemovePropertyService.PROPERTIES, allKeys));
  }

  @Override
  @SkipAsAnonymousMethod
  default GraphTraversal<S, E> from(String fromStepLabel) {
    if (fromStepLabel != null && !fromStepLabel.isBlank() && fromStepLabel.charAt(0) == '#') {
      var rid = RID.of(fromStepLabel);
      var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
      return ytdbGraphTraversal.from(__.V(rid));
    }

    return GraphTraversal.Admin.super.from(fromStepLabel);
  }

  @SkipAsAnonymousMethod
  @Override
  default GraphTraversal<S, E> to(String toStepLabel) {
    if (toStepLabel != null && !toStepLabel.isBlank() && toStepLabel.charAt(0) == '#') {
      var rid = RID.of(toStepLabel);
      var ytdbGraphTraversal = (YTDBGraphTraversal<S, E>) this;
      return ytdbGraphTraversal.to(__.V(rid));
    }

    return GraphTraversal.Admin.super.to(toStepLabel);
  }
}
