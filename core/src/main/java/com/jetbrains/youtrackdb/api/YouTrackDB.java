package com.jetbrains.youtrackdb.api;


import com.jetbrains.youtrackdb.api.common.BasicYouTrackDB;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.query.Result;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public interface YouTrackDB extends BasicYouTrackDB<Result, DatabaseSession> {

  /// Creates the database by restoring it from backup. The backup should be created with
  /// [DatabaseSession#backup(Supplier, Function, Function, Consumer)] or
  /// [DatabaseSession#backup(Path)] methods.
  ///
  /// At the moment only disk-based databases are supported, you cannot restore memory databases.
  ///
  /// This method can be used to restore a database from the backup located on abstract backup
  /// storage, on a remote server, for example.
  ///
  /// It does not operate by concepts of a local file system instead it accepts lambda instances to
  /// operate by backup files.
  ///
  /// @param name                   Name of a database to be created.
  /// @param ibuFilesSupplier       Lambda that will provide a list of the backup files used to
  ///                               restore the database.
  /// @param ibuInputStreamSupplier lambda that will provide an input stream for each backup file.
  /// @param expectedUUID           UUID of the database to be restored. If null, the database will
  ///                               be restored only if the directory contains backup only from one
  ///                               database.
  /// @param config                 YouTrackDB config, the same as for
  ///                               [#create(String,DatabaseType,YouTrackDBConfig)]
  void restore(String name, final Supplier<Iterator<String>> ibuFilesSupplier,
      Function<String, InputStream> ibuInputStreamSupplier, @Nullable String expectedUUID,
      YouTrackDBConfig config);

}
