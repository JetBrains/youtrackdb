package com.jetbrain.youtrack.db.gremlin.internal.traversal.step.sideeffect;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphBaseQuery;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphInternal;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBGraphQueryBuilder;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBStatefulEdge;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBVertex;
import com.jetbrains.youtrack.db.api.query.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.DefaultCloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public class YTDBGraphStep<S, E extends Element> extends GraphStep<S, E>
    implements HasContainerHolder {

  private final List<HasContainer> hasContainers = new ArrayList<>();

  public YTDBGraphStep(final GraphStep<S, E> originalGraphStep) {
    super(
        originalGraphStep.getTraversal(),
        originalGraphStep.getReturnClass(),
        originalGraphStep.isStartStep(),
        originalGraphStep.getIds());
    originalGraphStep.getLabels().forEach(this::addLabel);
    //noinspection unchecked
    this.setIteratorSupplier(
        () -> isVertexStep() ? (Iterator<E>) this.vertices() : (Iterator<E>) this.edges());
  }

  public boolean isVertexStep() {
    return Vertex.class.isAssignableFrom(this.returnClass);
  }

  private Iterator<? extends Vertex> vertices() {
    return elements(
        YTDBGraph::vertices, YTDBGraph::vertices,
        result -> new YTDBVertex(getGraph(), result.asVertex()));
  }

  private Iterator<? extends Edge> edges() {
    return elements(
        YTDBGraph::edges, YTDBGraph::edges,
        result -> new YTDBStatefulEdge(getGraph(), result.asStatefulEdge()));
  }

  /**
   * Gets an iterator over those vertices/edges that have the specific IDs wanted, or those that
   * have indexed properties with the wanted values, or failing that by just getting all of the
   * vertices or edges.
   *
   * @param getElementsByIds Function that will return an iterator over all the vertices/edges in
   *                         the graph that have the specific IDs
   * @param getAllElements   Function that returns an iterator of all the vertices or all the edges
   *                         (i.e. full scan)
   * @return An iterator for all the vertices/edges for this step
   */
  private <ElementType extends Element> Iterator<? extends ElementType> elements(
      BiFunction<YTDBGraph, Object[], Iterator<ElementType>> getElementsByIds,
      Function<YTDBGraph, Iterator<ElementType>> getAllElements,
      Function<Result, ElementType> getElement) {
    final var graph = getGraph();
    graph.tx().readWrite();

    if (this.ids != null && this.ids.length > 0) {
      /* Got some element IDs, so just get the elements using those */
      return this.iteratorList(getElementsByIds.apply(graph, this.ids));
    } else {
      var query = buildQuery();
      if (query != null) {
        return new DefaultCloseableIterator<>(query.execute(getGraph()).stream()
            .map(getElement)
            .filter(element -> HasContainer.testAll(element, this.hasContainers)).iterator());
      }

      return this.iteratorList(getAllElements.apply(graph));
    }
  }

  private YTDBGraphInternal getGraph() {
    return (YTDBGraphInternal) this.getTraversal().getGraph().orElseThrow();
  }

  @Nullable
  public YTDBGraphBaseQuery buildQuery() {
    var builder = new YTDBGraphQueryBuilder(isVertexStep());
    this.hasContainers.forEach(builder::addCondition);
    return builder.build(getGraph());
  }

  @Override
  public String toString() {
    if (this.hasContainers.isEmpty()) {
      return super.toString();
    } else {
      return 0 == this.ids.length
          ? StringFactory.stepString(
          this, this.returnClass.getSimpleName().toLowerCase(), this.hasContainers)
          : StringFactory.stepString(
              this,
              this.returnClass.getSimpleName().toLowerCase(),
              Arrays.toString(this.ids),
              this.hasContainers);
    }
  }

  private <X extends Element> Iterator<X> iteratorList(final Iterator<X> iterator) {
    final List<X> list = new ArrayList<>();
    while (iterator.hasNext()) {
      final var e = iterator.next();
      if (HasContainer.testAll(e, this.hasContainers)) {
        list.add(e);
      }
    }
    return list.iterator();
  }

  @Override
  public List<HasContainer> getHasContainers() {
    return Collections.unmodifiableList(this.hasContainers);
  }

  @Override
  public void addHasContainer(final HasContainer hasContainer) {
    this.hasContainers.add(hasContainer);
  }
}
