package com.jetbrains.youtrackdb.api.gremlin;

import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassOutToken.parentClass;
import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassPToken.abstractClass;
import static com.jetbrains.youtrackdb.api.gremlin.domain.tokens.schema.YTDBSchemaClassPToken.name;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.AddVertexStartStep;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;

public class YTDBGraphTraversalSourceDSL extends GraphTraversalSource {

  public YTDBGraphTraversalSourceDSL(Graph graph,
      TraversalStrategies traversalStrategies) {
    super(graph, traversalStrategies);
  }

  public YTDBGraphTraversalSourceDSL(Graph graph) {
    super(graph);
  }

  public YTDBGraphTraversalSourceDSL(
      RemoteConnection connection) {
    super(connection);
  }

  public YTDBGraphTraversalSource with(final YTDBQueryConfigParam key, final Object value) {
    if (!key.type().isInstance(value)) {
      throw new IllegalArgumentException("The provided value " + value + " is not an instance of "
          + key.type().getSimpleName());
    }
    return (YTDBGraphTraversalSource) with(key.name(), value);
  }

  public YTDBGraphTraversal<Vertex, Vertex> addSchemaClass(String className) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    clone.getBytecode().addStep(GraphTraversal.Symbols.addV);

    var traversal = new DefaultYTDBGraphTraversal<Vertex, Vertex>(clone);
    traversal.addStep(new AddVertexStartStep(traversal, YTDBSchemaClass.LABEL));
    traversal.property(name, className);

    return traversal;
  }

  public YTDBGraphTraversal<Vertex, Vertex> addSchemaClass(String className,
      String... parentClasses) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    clone.getBytecode().addStep(GraphTraversal.Symbols.addV);

    var traversal = new DefaultYTDBGraphTraversal<Vertex, Vertex>(clone);
    traversal.addStep(new AddVertexStartStep(traversal, YTDBSchemaClass.LABEL));

    return traversal.addV(YTDBSchemaClass.LABEL).as("result")
        .addE(parentClass).to(__.V().hasLabel(YTDBSchemaClass.LABEL).
            has(name, P.within(parentClasses)))
        .select("result");
  }

  public YTDBGraphTraversal<Vertex, Vertex> addAbstractSchemaClass(String className) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    clone.getBytecode().addStep(GraphTraversal.Symbols.addV);

    var traversal = new DefaultYTDBGraphTraversal<Vertex, Vertex>(clone);
    traversal.addStep(new AddVertexStartStep(traversal, YTDBSchemaClass.LABEL));

    return traversal.property(name, className, abstractClass, true);
  }

  public YTDBGraphTraversal<Vertex, Vertex> addAbstractSchemaClass(String className,
      String... superClasses) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    clone.getBytecode().addStep(GraphTraversal.Symbols.addV);

    var traversal = new DefaultYTDBGraphTraversal<Vertex, Vertex>(clone);
    traversal.addStep(new AddVertexStartStep(traversal, YTDBSchemaClass.LABEL));

    return traversal.as("result").
        property(name, className, abstractClass, true).
        addE(parentClass).to(__.V()
            .hasLabel(YTDBSchemaClass.LABEL).has(name, P.within(superClasses)))
        .select("result");
  }

  public YTDBGraphTraversal<Vertex, Vertex> addStateFullEdgeClass(String className) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    clone.getBytecode().addStep(GraphTraversal.Symbols.addV);

    var traversal = new DefaultYTDBGraphTraversal<Vertex, Vertex>(clone);
    traversal.addStep(new AddVertexStartStep(traversal, YTDBSchemaClass.LABEL));

    return traversal.addV(YTDBSchemaClass.LABEL).property(name, className).as("schemaClass").
        addE(parentClass).to(
            __.V().hasLabel(YTDBSchemaClass.LABEL)
                .has(name, P.eq(YTDBSchemaClass.EDGE_CLASS_NAME))
        ).select("schemaClass");
  }


  public YTDBGraphTraversalSource with(final YTDBQueryConfigParam key) {
    return with(key, true);
  }

  /// Start a new transaction if it is not yet started and executes passed in code in it.
  ///
  /// If a transaction is already started, executes passed in code in it. In case of exception,
  /// rolls back the transaction and commits the changes if the transaction was started by this
  /// method.
  public <X extends Exception> void executeInTx(
      @Nonnull FailableConsumer<YTDBGraphTraversalSource, X> code) throws X {
    var tx = tx();
    YTDBTransaction.executeInTX(code, (YTDBTransaction) tx);
  }

  /// Start a new transaction if it is not yet started and executes passed in code in it.
  ///
  /// If a transaction is already started, executes passed in code in it. In case of exception,
  /// rolls back the transaction and commits the changes if the transaction was started by this
  /// method.
  ///
  /// Unlike {@link #executeInTx(FailableConsumer)} also iterates over the returned
  /// [YTDBGraphTraversal] triggering its execution.
  public <X extends Exception> void autoExecuteInTx(
      @Nonnull FailableFunction<YTDBGraphTraversalSource, YTDBGraphTraversal<?, ?>, X> code)
      throws X {
    var tx = tx();
    YTDBTransaction.executeInTX(code, (YTDBTransaction) tx);
  }

  /// Start a new transaction if it is not yet started and executes passed in code in it and then
  /// returns the result of the code execution.
  ///
  /// If a transaction is already started, executes passed in code in it. In case of exception,
  /// rolls back the transaction and commits the changes if the transaction was started by this
  /// method.
  public <X extends Exception, R> R computeInTx(
      @Nonnull FailableFunction<YTDBGraphTraversalSource, R, X> code) throws X {
    var tx = tx();
    return YTDBTransaction.computeInTx(code, (YTDBTransaction) tx);
  }
}
