package com.jetbrains.youtrack.db.internal.core.gremlin.traversal.step.sideeffect;


import com.jetbrains.youtrack.db.api.gremlin.YTDBGraph;
import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphBaseQuery;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBGraphQueryBuilder;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBSchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBVertexImpl;
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
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.DefaultCloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.apache.tinkerpop.gremlin.util.iterator.MultiIterator;

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
    var graph = getGraph();

    var userVertices = elements(
        YTDBGraph::vertices, YTDBGraph::vertices,
        result -> new YTDBVertexImpl(graph, result.asVertex()));

    var schemaVertices = createClassIterator(graph);
    if (schemaVertices == null) {
      return userVertices;
    }

    var multiIterator = new MultiIterator<Vertex>();
    //noinspection unchecked
    multiIterator.addIterator((Iterator<Vertex>) userVertices);
    multiIterator.addIterator(schemaVertices);

    return multiIterator;
  }

  private Iterator<? extends Edge> edges() {
    var graph = getGraph();
    return elements(
        YTDBGraph::edges, YTDBGraph::edges,
        result -> new YTDBStatefulEdgeImpl(graph, result.asStatefulEdge()));
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
    var tx = graph.tx();
    tx.readWrite();

    var session = tx.getDatabaseSession();

    if (this.ids != null && this.ids.length > 0) {
      /* Got some element IDs, so just get the elements using those */
      return IteratorUtils.filter(getElementsByIds.apply(graph, this.ids),
          element -> HasContainer.testAll(element, this.hasContainers));
    } else {
      var query = buildQuery(session);
      if (query != null) {
        return new DefaultCloseableIterator<>(query.execute(session).stream()
            .map(getElement)
            .filter(element -> HasContainer.testAll(element, this.hasContainers)).iterator());
      }

      return IteratorUtils.filter(getAllElements.apply(graph),
          element -> HasContainer.testAll(element, this.hasContainers));
    }
  }

  private YTDBGraphInternal getGraph() {
    return (YTDBGraphInternal) this.getTraversal().getGraph().orElseThrow();
  }

  @Nullable
  private Iterator<Vertex> createClassIterator(YTDBGraphInternal graph) {
    for (var hasContainer : this.hasContainers) {
      if (T.label.getAccessor().equals(hasContainer.getKey()) && YTDBSchemaClass.LABEL.equals(
          hasContainer.getValue())) {
        var tx = graph.tx();
        var session = tx.getDatabaseSession();

        return IteratorUtils.map(
            session.getSharedContext().getSchema().getClasses(session).iterator(),
            schemaClass -> new YTDBSchemaClassImpl(schemaClass, graph));
      }
    }

    return null;
  }

  @Nullable
  public YTDBGraphBaseQuery buildQuery(DatabaseSessionEmbedded session) {
    var builder = new YTDBGraphQueryBuilder(isVertexStep());
    this.hasContainers.forEach(builder::addCondition);
    return builder.build(session);
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


  @Override
  public List<HasContainer> getHasContainers() {
    return Collections.unmodifiableList(this.hasContainers);
  }

  @Override
  public void addHasContainer(final HasContainer hasContainer) {
    this.hasContainers.add(hasContainer);
  }
}
