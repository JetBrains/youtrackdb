package com.jetbrains.youtrack.db.internal.lucene.engine;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.RAMDirectory;

/**
 *
 */
public class LuceneDirectoryFactory {

  public static final String OLUCENE_BASE_DIR = "luceneIndexes";

  public static final String DIRECTORY_TYPE = "directory_type";

  public static final String DIRECTORY_NIO = "nio";
  public static final String DIRECTORY_MMAP = "mmap";
  public static final String DIRECTORY_RAM = "ram";

  public static final String DIRECTORY_PATH = "directory_path";

  public LuceneDirectory createDirectory(
      final Storage storage, final String indexName, final Map<String, ?> metadata) {
    final var luceneType =
        metadata.containsKey(DIRECTORY_TYPE) ? metadata.get(DIRECTORY_TYPE).toString()
            : DIRECTORY_MMAP;
    if (storage.getType().equals(DatabaseType.MEMORY.name().toLowerCase())
        || DIRECTORY_RAM.equals(luceneType)) {
      final Directory dir = new RAMDirectory();
      return new LuceneDirectory(dir, null);
    }
    return createDirectory(storage, indexName, metadata, luceneType);
  }

  private LuceneDirectory createDirectory(
      final Storage storage,
      final String indexName,
      final Map<String, ?> metadata,
      final String luceneType) {
    final String luceneBasePath;
    if (metadata.containsKey(DIRECTORY_PATH)) {
      luceneBasePath = metadata.get(DIRECTORY_PATH).toString();
    } else {
      luceneBasePath = OLUCENE_BASE_DIR;
    }
    final var luceneIndexPath =
        Paths.get(storage.getConfiguration().getDirectory(), luceneBasePath, indexName);
    try {
      Directory dir = null;
      if (DIRECTORY_NIO.equals(luceneType)) {
        dir = new NIOFSDirectory(luceneIndexPath);
      } else if (DIRECTORY_MMAP.equals(luceneType)) {
        dir = new MMapDirectory(luceneIndexPath);
      }
      return new LuceneDirectory(dir, luceneIndexPath.toString());
    } catch (final IOException e) {
      LogManager.instance()
          .error(this, "unable to create Lucene Directory with type " + luceneType, e);
    }
    LogManager.instance().warn(this, "unable to create Lucene Directory, FALL BACK to ramDir");
    return new LuceneDirectory(new RAMDirectory(), null);
  }
}
