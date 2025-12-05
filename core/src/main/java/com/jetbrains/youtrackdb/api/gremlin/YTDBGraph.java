package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBServices;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.service.ServiceRegistry;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

@GraphFactoryClass(YTDBGraphFactory.class)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@Graph.OptIn("com.jetbrains.youtrackdb.internal.server.plugin.gremlin.process.YTDBProcessTestSuite")
public interface YTDBGraph extends Graph {

  @Override
  default ServiceRegistry getServiceRegistry() {
    return YTDBServices.REGISTRY;
  }

  @Override
  YTDBVertex addVertex(Object... keyValues);

  @Override
  YTDBVertex addVertex(String label);

  @Override
  default YTDBGraphTraversalSource traversal() {
    return new YTDBGraphTraversalSource(this);
  }

  /// Start a new transaction if it is not yet started and executes passed in code in it.
  ///
  /// If a transaction is already started, executes passed in code in it. In case of exception,
  /// rolls back the transaction and commits the changes if the transaction was started by this
  /// method.
  default <X extends Exception> void executeInTx(
      @Nonnull FailableConsumer<YTDBGraphTraversalSource, X> code) throws X {
    YTDBTransaction.executeInTX(code, traversal());
  }

  /// Start a new transaction if it is not yet started and executes passed in code in it and then
  /// returns the result of the code execution.
  ///
  /// If a transaction is already started, executes passed in code in it. In case of exception,
  /// rolls back the transaction and commits the changes if the transaction was started by this
  /// method.
  default <X extends Exception, R> R computeInTx(
      @Nonnull FailableFunction<YTDBGraphTraversalSource, R, X> code) throws X {
    var tx = tx();
    return YTDBTransaction.computeInTx(code, traversal());
  }

  /// Start a new transaction if it is not yet started and executes passed in code in it.
  ///
  /// If a transaction is already started, executes passed in code in it. In case of exception,
  /// rolls back the transaction and commits the changes if the transaction was started by this
  /// method.
  ///
  /// Unlike {@link #executeInTx(FailableConsumer)} also iterates over the returned
  /// [YTDBGraphTraversal] triggering its execution.
  default <X extends Exception> void autoExecuteInTx(
      @Nonnull FailableFunction<YTDBGraphTraversalSource, YTDBGraphTraversal<?, ?>, X> code)
      throws X {
    var tx = tx();
    YTDBTransaction.executeInTX(code, traversal());
  }
}
