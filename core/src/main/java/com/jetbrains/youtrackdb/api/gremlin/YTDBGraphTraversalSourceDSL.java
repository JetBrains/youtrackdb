package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBCommandService;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBFullBackupService;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBGraphUuidService;
import com.jetbrains.youtrackdb.internal.core.gremlin.service.YTDBIncrementalBackupService;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CallStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IoStep;
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
    YTDBTransaction.executeInTX(code, (YTDBGraphTraversalSource) this);
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
    YTDBTransaction.executeInTX(code, (YTDBGraphTraversalSource) this);
  }

  /// Start a new transaction if it is not yet started and executes passed in code in it and then
  /// returns the result of the code execution.
  ///
  /// If a transaction is already started, executes passed in code in it. In case of exception,
  /// rolls back the transaction and commits the changes if the transaction was started by this
  /// method.
  public <X extends Exception, R> R computeInTx(
      @Nonnull FailableFunction<YTDBGraphTraversalSource, R, X> code) throws X {
    return YTDBTransaction.computeInTx(code, (YTDBGraphTraversalSource) this);
  }

  /// Execute a generic YouTrackDB command. Returns a traversal that can be chained.
  /// Schema commands (CREATE CLASS, DROP CLASS, etc.) are executed immediately for embedded graphs.
  ///
  /// @param command The command to execute.
  /// @return A traversal that can be chained with other steps.
  public <S> GraphTraversal<S, S> command(@Nonnull String command) {
    return command(command, Map.of());
  }

  /// Execute a generic parameterized YouTrackDB command. Returns a traversal that can be chained.
  /// Schema commands (CREATE CLASS, DROP CLASS, etc.) are executed immediately.
  ///
  /// @param command   The command to execute.
  /// @param arguments The arguments to pass to the command.
  /// @return A traversal that can be chained with other steps.
  public <S> GraphTraversal<S, S> command(@Nonnull String command, @Nonnull Map<?, ?> arguments) {
    // For schema commands, execute immediately (both embedded and remote)
    if (isSchemaCommand(command)) {
      if (graph instanceof YTDBGraphInternal ytdbGraph) {
        // Embedded: execute directly
        ytdbGraph.executeCommand(command, arguments);
      } else {
        // Remote: execute via call() and iterate immediately
        call(
            YTDBCommandService.NAME, Map.of(
                YTDBCommandService.COMMAND, command,
                YTDBCommandService.ARGUMENTS, arguments
            )
        ).iterate();
      }
      //noinspection unchecked
      return (GraphTraversal<S, S>) inject();
    }

    // Non-schema commands: return lazy traversal for chaining
    //noinspection unchecked
    return call(
        YTDBCommandService.NAME, Map.of(
            YTDBCommandService.COMMAND, command,
            YTDBCommandService.ARGUMENTS, arguments
        )
    );
  }

  private static boolean isSchemaCommand(String command) {
    if (command == null) {
      return false;
    }
    var normalized = command.trim().toUpperCase();
    return normalized.startsWith("CREATE CLASS")
        || normalized.startsWith("DROP CLASS")
        || normalized.startsWith("ALTER CLASS")
        || normalized.startsWith("CREATE PROPERTY")
        || normalized.startsWith("DROP PROPERTY")
        || normalized.startsWith("CREATE INDEX")
        || normalized.startsWith("DROP INDEX")
        || normalized.startsWith("CREATE VERTEX")
        || normalized.startsWith("CREATE EDGE");
  }

  /// Performs backup of database content to the selected folder.
  ///
  /// During the first backup full content of the database will be copied into the directory,
  /// otherwise only changes after the last backup in the same folder will be copied.
  ///
  /// @param path Path to the backup folder.
  /// @return The name of the last backup file.
  public String backup(final Path path) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    var params = Map.of(YTDBIncrementalBackupService.PATH, path.toString());
    clone.getBytecode().addStep("call", YTDBIncrementalBackupService.NAME, params);

    try (var traversal = new DefaultYTDBGraphTraversal<>(clone)) {
      return (String)
          traversal.addStep(
                  new CallStep<>(traversal, true, YTDBIncrementalBackupService.NAME, params))
              .next();
    } catch (Exception e) {
      throw BaseException.wrapException(new DatabaseException("Error during incremental backup"), e,
          graph.toString());
    }
  }

  /// Performs backup of database content to the selected folder.
  ///
  /// If the incremental backup is present in the folder, it will be overwritten.
  ///
  /// @param path Path to the backup folder.
  /// @return The name of the backup file.
  public String fullBackup(final Path path) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    var params = Map.of(YTDBFullBackupService.PATH, path.toString());
    clone.getBytecode().addStep("call", YTDBFullBackupService.NAME, params);

    try (var traversal = new DefaultYTDBGraphTraversal<>(clone)) {
      return (String)
          traversal.addStep(
                  new CallStep<>(traversal, true, YTDBFullBackupService.NAME, params))
              .next();
    } catch (Exception e) {
      throw BaseException.wrapException(new DatabaseException("Error during full backup"), e,
          graph.toString());
    }
  }

  /// Returns [UUID] generated during the creation of the database. Each DB instance his unique
  /// [UUID] identifier.
  ///
  /// It is used during the generation of backups and later restores to identify the database
  /// instance.
  public UUID uuid() {
    var clone = (YTDBGraphTraversalSource) this.clone();
    clone.getBytecode().addStep("call", YTDBGraphUuidService.NAME, Map.of());

    try (var traversal = new DefaultYTDBGraphTraversal<>(clone)) {
      return UUID.fromString((String)
          traversal.addStep(
                  new CallStep<>(traversal, true, YTDBGraphUuidService.NAME, Map.of()))
              .next());
    } catch (Exception e) {
      throw BaseException.wrapException(
          new DatabaseException("Error during retrieving database uuid"), e,
          graph.toString());
    }
  }

  @Override
  public <S> YTDBGraphTraversal<S, S> io(final String file) {
    var clone = (YTDBGraphTraversalSource) this.clone();
    clone.getBytecode().addStep("io", file);
    var traversal = new DefaultYTDBGraphTraversal<>(clone);
    traversal.addStep(new IoStep<>(traversal, file));
    //noinspection unchecked
    return (YTDBGraphTraversal<S, S>) traversal;
  }

  @Override
  public void close() {
    try {
      super.close();
    } catch (Exception e) {
      throw new RuntimeException("Error during closing of GraphTraversalSource", e);
    }
  }
}
