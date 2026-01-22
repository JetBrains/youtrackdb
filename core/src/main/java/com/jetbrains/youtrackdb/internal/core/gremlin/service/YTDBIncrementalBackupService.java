package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

/// Service that performs incremental backup of the database to a specified path.
public class YTDBIncrementalBackupService implements Service<String, String> {

  public static final String NAME = "ytdbIncrementalBackup";
  public static final String PATH = "path";

  @Nonnull
  private final Path path;

  public YTDBIncrementalBackupService(@Nonnull Path path) {
    this.path = path;
  }

  @Override
  public Type getType() {
    return Type.Start;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return Set.of();
  }

  @Override
  public CloseableIterator<String> execute(ServiceCallContext ctx, Map params) {
    final var graph = (((Admin<?, ?>) ctx.getTraversal()))
        .getGraph()
        .orElseThrow(() -> new IllegalStateException("Graph is not available"));

    var backupPath = (((YTDBGraph) graph)).backup(path);
    return CloseableIterator.of(IteratorUtils.of(backupPath));
  }

  public static class Factory implements ServiceFactory<String, String> {

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      return Set.of(Type.Start);
    }

    @Override
    public Service<String, String> createService(boolean isStart, Map params) {
      if (!isStart) {
        throw new UnsupportedOperationException(Exceptions.cannotUseMidTraversal);
      }

      final String path;

      if (params.get(PATH) instanceof String c) {
        path = c;
      } else {
        throw new IllegalArgumentException(params.get(PATH) + " is not a String");
      }

      return new YTDBIncrementalBackupService(Paths.get(path));
    }
  }
}
