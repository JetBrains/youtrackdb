package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.map;

import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;

public class YTDBClassCountStep<S> extends AbstractStep<S, Long> {

  private boolean vertexStep;
  private List<String> klasses;
  private boolean polymorphic;

  protected boolean done = false;

  public YTDBClassCountStep(
      Traversal.Admin traversal,
      List<String> klasses,
      boolean vertexStep,
      boolean polymorphic
  ) {
    super(traversal);
    this.klasses = klasses;
    this.vertexStep = vertexStep;
    this.polymorphic = polymorphic;
  }

  @Override
  protected Traverser.Admin<Long> processNextStart() throws NoSuchElementException {
    if (!done) {
      done = true;
      var session = getDatabaseSession();
      Long v =
          klasses.stream()
              .filter(this::filterClass)
              .mapToLong(cl -> session.countClass(cl, polymorphic))
              .reduce(0, Long::sum);
      //noinspection unchecked
      return this.traversal.getTraverserGenerator().generate(v, (Step<Long, ?>) this, 1L);
    } else {
      throw FastNoSuchElementException.instance();
    }
  }

  private DatabaseSessionEmbedded getDatabaseSession() {
    var graph = (YTDBGraphInternal) this.traversal.getGraph().orElseThrow();
    var graphTx = graph.tx();
    graphTx.readWrite();
    return graphTx.getDatabaseSession();
  }

  private boolean filterClass(String klass) {
    var session = getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    assert schema != null;

    var schemaClass = schema.getClass(klass);

    if (schemaClass == null) {
      return false;
    } else if (vertexStep) {
      return schemaClass.isSubClassOf(SchemaClass.VERTEX_CLASS_NAME);
    } else {
      return schemaClass.isSubClassOf(SchemaClass.EDGE_CLASS_NAME);
    }
  }

  public List<String> getKlasses() {
    return klasses;
  }

  @Override
  public YTDBClassCountStep<S> clone() {
    var newCount = (YTDBClassCountStep<S>) super.clone();
    newCount.klasses = this.klasses;
    newCount.vertexStep = this.vertexStep;
    newCount.done = false;
    newCount.polymorphic = this.polymorphic;
    return newCount;
  }
}
