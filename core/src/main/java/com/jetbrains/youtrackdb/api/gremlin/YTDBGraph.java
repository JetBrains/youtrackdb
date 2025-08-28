package com.jetbrains.youtrackdb.api.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactoryClass;

@GraphFactoryClass(YTDBGraphFactory.class)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@Graph.OptIn("com.jetbrains.youtrackdb.internal.server.plugin.gremlin.process.YTDBProcessTestSuite")
public interface YTDBGraph extends Graph {

  @Override
  YTDBVertex addVertex(Object... keyValues);

  @Override
  YTDBVertex addVertex(String label);

  /// Performs backup of database content to the selected folder. This is a thread-safe operation
  /// and can be done in normal operational mode.
  ///
  /// During the first backup full content of the database will be copied into the folder, otherwise
  /// only changes after the last backup in the same folder will be copied.
  ///
  /// Unlike [#backup(Path)] method, this method can be used to perform backup to the abstract
  /// backup storage to the remote server, for example.
  ///
  /// As this method does not operate by concepts of the file system but instead accepts lambdas
  /// that provide access to the content of backup.
  ///
  /// @param ibuFilesSupplier        Lamba that provides the list of backup files already created.
  /// @param ibuInputStreamSupplier  Lamba that provides the input stream for the backup file.
  /// @param ibuOutputStreamSupplier Lamba that provides the output stream for the backup file.
  /// @param ibuFileRemover          Lamba that removes the backup file from the backup storage.
  ///
  void backup(final Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier,
      Function<String, OutputStream> ibuOutputStreamSupplier,
      final Consumer<String> ibuFileRemover);

  /// Performs backup of database content to the selected folder. This is a thread-safe operation
  /// and can be done in normal operational mode.
  ///
  /// During the first backup full content of the database will be copied into the directory,
  /// otherwise only changes after the last backup in the same folder will be copied.
  ///
  /// @param path Path to the backup folder.
  void backup(Path path);


  @Override
  default GraphTraversalSource traversal() {
    return new YTDBGraphTraversalSource(this);
  }
}
