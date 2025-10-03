package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.service.YTDBCommandService;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;

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

  /// Execute a generic YouTrackDB command. The result of the execution is ignored, so it only makes
  /// sense to use this method for running non-idempotent commands.
  ///
  /// @param command The command to execute.
  public void command(@Nonnull String command) {
    command(command, Map.of());
  }

  /// Execute a generic parameterized YouTrackDB command. The result of the execution is ignored, so
  /// it only makes sense to use this method for running non-idempotent commands.
  ///
  /// @param command   The command to execute.
  /// @param arguments The arguments to pass to the command.
  public void command(@Nonnull String command, @Nonnull Map<?, ?> arguments) {
    call(
        YTDBCommandService.NAME, Map.of(
            YTDBCommandService.COMMAND, command,
            YTDBCommandService.ARGUMENTS, arguments
        )
    ).iterate();
  }
}
