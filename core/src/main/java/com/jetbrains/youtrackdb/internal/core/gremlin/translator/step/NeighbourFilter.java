package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBCollatedHasContainer;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter.YTDBLabelMatcher;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.T;

/** Native-equivalent post-hop predicate, resolved against each neighbour's current schema. */
sealed interface NeighbourFilter permits NeighbourFilter.Labels, NeighbourFilter.Container {

  boolean test(Element element);

  /** OR within each HasStep, AND across steps and non-label containers. */
  static List<NeighbourFilter> fromContainers(
      List<HasContainer> containers, List<Integer> stepSizes, boolean polymorphic) {
    var filters = new ArrayList<NeighbourFilter>();
    int offset = 0;
    for (int size : stepSizes) {
      var labels = new ArrayList<P<? super String>>();
      var properties = new ArrayList<NeighbourFilter>();
      for (var container : containers.subList(offset, offset + size)) {
        if (T.label.getAccessor().equals(container.getKey())) {
          @SuppressWarnings("unchecked")
          P<? super String> predicate = (P<? super String>) container.getPredicate();
          labels.add(predicate);
        } else {
          properties.add(new Container(YTDBCollatedHasContainer.wrap(container)));
        }
      }
      if (!labels.isEmpty()) {
        filters.add(new Labels(List.copyOf(labels), polymorphic));
      }
      filters.addAll(properties);
      offset += size;
    }
    if (offset != containers.size()) {
      throw new IllegalArgumentException("HasStep sizes must cover every neighbour container");
    }
    return List.copyOf(filters);
  }

  record Labels(List<P<? super String>> predicates, boolean polymorphic)
      implements NeighbourFilter {

    @Override
    public boolean test(Element element) {
      return YTDBLabelMatcher.matchesAny(element, predicates, polymorphic);
    }
  }

  record Container(HasContainer container) implements NeighbourFilter {

    @Override
    public boolean test(Element element) {
      return container.test(element);
    }
  }
}
