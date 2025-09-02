/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrackdb.internal.lucene.engine;

import static com.jetbrains.youtrackdb.internal.lucene.analyzer.LuceneAnalyzerFactory.AnalyzerKind.INDEX;
import static com.jetbrains.youtrackdb.internal.lucene.analyzer.LuceneAnalyzerFactory.AnalyzerKind.QUERY;

import com.fasterxml.jackson.core.JsonFactory;
import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.lucene.analyzer.LuceneAnalyzerFactory;
import com.jetbrains.youtrackdb.internal.lucene.builder.LuceneIndexType;
import com.jetbrains.youtrackdb.internal.lucene.exception.LuceneIndexException;
import com.jetbrains.youtrackdb.internal.lucene.query.LuceneQueryContext;
import com.jetbrains.youtrackdb.internal.lucene.tx.LuceneTxChanges;
import com.jetbrains.youtrackdb.internal.lucene.tx.LuceneTxChangesMultiRid;
import com.jetbrains.youtrackdb.internal.lucene.tx.LuceneTxChangesSingleRid;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LuceneIndexEngineAbstract implements LuceneIndexEngine {

  private static final Logger logger = LoggerFactory.getLogger(LuceneIndexEngineAbstract.class);

  public static final String RID = "RID";
  public static final String KEY = "KEY";

  private final AtomicLong lastAccess;
  private SearcherManager searcherManager;
  protected IndexDefinition indexDefinition;
  protected String name;
  private ControlledRealTimeReopenThread<IndexSearcher> nrt;
  protected Map<String, ?> metadata;
  protected Version version;
  protected Map<String, Boolean> collectionFields = new HashMap<>();
  private TimerTask commitTask;
  private final AtomicBoolean closed;
  private final Storage storage;
  private volatile long reopenToken;
  private Analyzer indexAnalyzer;
  private Analyzer queryAnalyzer;
  private volatile LuceneDirectory directory;
  private IndexWriter indexWriter;
  private long flushIndexInterval;
  private long closeAfterInterval;
  private long firstFlushAfter;
  private final int id;

  public LuceneIndexEngineAbstract(int id, Storage storage, String name) {
    super();
    this.id = id;

    this.storage = storage;
    this.name = name;

    lastAccess = new AtomicLong(System.currentTimeMillis());

    closed = new AtomicBoolean(true);
  }

  @Override
  public int getId() {
    return id;
  }

  protected void updateLastAccess() {
    lastAccess.set(System.currentTimeMillis());
  }

  protected void addDocument(Document doc) {
    try {

      reopenToken = indexWriter.addDocument(doc);
    } catch (IOException e) {
      LogManager.instance()
          .error(this, "Error on adding new entity '%s' to Lucene index", e, doc);
    }
  }

  @Override
  public void init(DatabaseSessionInternal session, IndexMetadata im) {
    this.indexDefinition = im.getIndexDefinition();
    this.metadata = im.getMetadata();

    var fc = new LuceneAnalyzerFactory();
    indexAnalyzer = fc.createAnalyzer(indexDefinition, INDEX, metadata);
    queryAnalyzer = fc.createAnalyzer(indexDefinition, QUERY, metadata);

    checkCollectionIndex(session, indexDefinition);

    flushIndexInterval =
        Optional.ofNullable((Integer) metadata.get("flushIndexInterval"))
            .orElse(10000)
            .longValue();

    closeAfterInterval =
        Optional.ofNullable((Integer) metadata.get("closeAfterInterval"))
            .orElse(120000)
            .longValue();

    firstFlushAfter =
        Optional.ofNullable((Integer) metadata.get("firstFlushAfter"))
            .orElse(10000)
            .longValue();
  }

  private void scheduleCommitTask() {
    commitTask =
        new TimerTask() {

          @Override
          public void run() {
            LuceneIndexEngineAbstract.this
                .storage
                .getContext()
                .execute(
                    () -> {
                      if (shouldClose()) {
                        synchronized (LuceneIndexEngineAbstract.this) {
                          // while on lock the index was opened
                          if (!shouldClose()) {
                            return;
                          }
                          doClose(false);
                        }
                      }
                      if (!closed.get()) {

                        LogManager.instance().debug(this, "Flushing index: " + indexName(), logger);
                        flush();
                      }
                    });
          }
        };
    this.storage.getContext().schedule(commitTask, firstFlushAfter, flushIndexInterval);
  }

  private boolean shouldClose() {
    return !(directory.getDirectory() instanceof RAMDirectory)
        && System.currentTimeMillis() - lastAccess.get() > closeAfterInterval;
  }

  private void checkCollectionIndex(DatabaseSessionInternal session,
      IndexDefinition indexDefinition) {
    var fields = indexDefinition.getProperties();

    var aClass =
        session.getMetadata().getSchema().getClass(indexDefinition.getClassName());
    for (var field : fields) {
      var property = aClass.getProperty(field);

      if (PropertyTypeInternal.convertFromPublicType(property.getType()).isEmbedded()
          && property.getLinkedType() != null) {
        collectionFields.put(field, true);
      } else {
        collectionFields.put(field, false);
      }
    }
  }

  private void reOpen(Storage storage) throws IOException {
    if (indexWriter != null
        && indexWriter.isOpen()
        && directory.getDirectory() instanceof RAMDirectory) {
      // don't waste time reopening an in memory index
      return;
    }

    open(storage);
  }

  private synchronized void open(Storage storage) throws IOException {

    if (!closed.get()) {
      return;
    }

    var directoryFactory = new LuceneDirectoryFactory();

    directory = directoryFactory.createDirectory(storage, name, metadata);

    indexWriter = createIndexWriter(directory.getDirectory());
    searcherManager = new SearcherManager(indexWriter, true, true, null);

    reopenToken = 0;

    startNRT();

    closed.set(false);

    flush();

    scheduleCommitTask();

    addMetadataDocumentIfNotPresent(storage);
  }

  private void addMetadataDocumentIfNotPresent(Storage storage) {

    final var searcher = searcher(storage);
    try {
      final var topDocs =
          searcher.search(new TermQuery(new Term("_CLASS", "JSON_METADATA")), 1);
      var jsonFactory = new JsonFactory();
      if (topDocs.totalHits == 0) {
        var metaAsJson = JSONSerializerJackson.INSTANCE.mapToJson(metadata);

        String defAsJson;
        var jsonWriter = new StringWriter();
        try (var jsonGenerator = jsonFactory.createGenerator(jsonWriter)) {
          indexDefinition.toJson(jsonGenerator);
          defAsJson = jsonWriter.toString();
        } catch (IOException e) {
          throw BaseException.wrapException(
              new SerializationException(storage.getName(),
                  "Error while converting index definition to JSON"), e, storage.getName());
        }

        var lMetaDoc = new Document();
        lMetaDoc.add(new StringField("_META_JSON", metaAsJson, Field.Store.YES));
        lMetaDoc.add(new StringField("_DEF_JSON", defAsJson, Field.Store.YES));
        lMetaDoc.add(
            new StringField(
                "_DEF_CLASS_NAME", indexDefinition.getClass().getCanonicalName(), Field.Store.YES));
        lMetaDoc.add(new StringField("_CLASS", "JSON_METADATA", Field.Store.YES));
        addDocument(lMetaDoc);
      }

    } catch (IOException e) {
      LogManager.instance().error(this, "Error while retrieving index metadata", e);
    } finally {
      release(storage, searcher);
    }
  }

  private void startNRT() {
    nrt = new ControlledRealTimeReopenThread<>(indexWriter, searcherManager, 60.00, 0.1);
    nrt.setDaemon(true);
    nrt.start();
  }

  private void closeNRT() {
    if (nrt != null) {
      nrt.interrupt();
      nrt.close();
    }
  }

  private void cancelCommitTask() {
    if (commitTask != null) {
      commitTask.cancel();
    }
  }

  private void closeSearchManager() throws IOException {
    if (searcherManager != null) {
      searcherManager.close();
    }
  }

  private void commitAndCloseWriter() throws IOException {
    if (indexWriter != null && indexWriter.isOpen()) {
      indexWriter.commit();
      indexWriter.close();
      closed.set(true);
    }
  }

  protected abstract IndexWriter createIndexWriter(Directory directory) throws IOException;

  @Override
  public synchronized void flush() {
    try {
      if (!closed.get() && indexWriter != null && indexWriter.isOpen()) {
        indexWriter.commit();
      }
    } catch (Exception e) {
      LogManager.instance().error(this, "Error on flushing Lucene index", e);
    }
  }

  @Override
  public void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException {
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
    try {
      updateLastAccess();
      openIfClosed(storage);

      if (indexWriter != null && indexWriter.isOpen()) {
        synchronized (this) {
          doClose(true);
        }
      }

      final var storageLocalAbstract = (AbstractStorage) storage;
      if (storageLocalAbstract instanceof DiskStorage diskStorage) {
        var storagePath = diskStorage.getStoragePath().toFile();
        deleteIndexFolder(storagePath);
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(), "Error during deletion of Lucene index " + name),
          e, storage.getName());
    }
  }

  private void deleteIndexFolder(File baseStoragePath) throws IOException {
    final var files = directory.getDirectory().listAll();
    for (var fileName : files) {
      directory.getDirectory().deleteFile(fileName);
    }
    directory.getDirectory().close();
    var indexPath = directory.getPath();
    if (indexPath != null) {
      var indexDir = new File(indexPath);
      final var fileSystem = FileSystems.getDefault();
      while (true) {
        if (Files.isSameFile(
            fileSystem.getPath(baseStoragePath.getCanonicalPath()),
            fileSystem.getPath(indexDir.getCanonicalPath()))) {
          break;
        }
        // delete only if dir is empty, otherwise stop deleting process
        // last index will remove all upper dirs
        final var indexDirFiles = indexDir.listFiles();
        if (indexDirFiles != null && indexDirFiles.length == 0) {
          FileUtils.deleteRecursively(indexDir, true);
          indexDir = indexDir.getParentFile();
        } else {
          break;
        }
      }
    }
  }

  @Override
  public String indexName() {
    return name;
  }

  @Override
  public abstract void onRecordAddedToResultSet(
      LuceneQueryContext queryContext, ContextualRecordId recordId, Document ret,
      ScoreDoc score);

  @Override
  public Analyzer indexAnalyzer() {
    return indexAnalyzer;
  }

  @Override
  public Analyzer queryAnalyzer() {
    return queryAnalyzer;
  }

  @Override
  public boolean remove(Storage storage, Object key, Identifiable value) {
    updateLastAccess();
    openIfClosed(storage);

    var query = deleteQuery(storage, key, value);
    if (query != null) {
      deleteDocument(query);
    }
    return true;
  }

  @Override
  public boolean remove(Storage storage, Object key) {
    updateLastAccess();
    openIfClosed(storage);
    try {
      final var query = new QueryParser("", queryAnalyzer()).parse((String) key);
      deleteDocument(query);
      return true;
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      LogManager.instance().error(this, "Lucene parsing exception", e);
    }
    return false;
  }

  void deleteDocument(Query query) {
    try {
      reopenToken = indexWriter.deleteDocuments(query);
      if (!indexWriter.hasDeletions()) {
        LogManager.instance()
            .error(
                this,
                "Error on deleting entity by query '%s' to Lucene index",
                new IndexException(storage.getName(), "Error deleting entity"),
                query);
      }
    } catch (IOException e) {
      LogManager.instance()
          .error(this, "Error on deleting entity by query '%s' to Lucene index", e, query);
    }
  }

  private boolean isCollectionDelete() {
    var collectionDelete = false;
    for (var aBoolean : collectionFields.values()) {
      collectionDelete = collectionDelete || aBoolean;
    }
    return collectionDelete;
  }

  protected synchronized void openIfClosed(Storage storage) {
    if (closed.get()) {
      try {
        reOpen(storage);
      } catch (final IOException e) {
        LogManager.instance().error(this, "error while opening closed index:: " + indexName(), e);
      }
    }
  }

  @Override
  public boolean isCollectionIndex() {
    return isCollectionDelete();
  }

  @Override
  public IndexSearcher searcher(Storage storage) {
    try {
      updateLastAccess();
      openIfClosed(storage);
      nrt.waitForGeneration(reopenToken);
      return searcherManager.acquire();
    } catch (Exception e) {
      LogManager.instance().error(this, "Error on get searcher from Lucene index", e);
      throw BaseException.wrapException(
          new LuceneIndexException("Error on get searcher from Lucene index"), e,
          storage.getName());
    }
  }

  @Override
  public long sizeInTx(LuceneTxChanges changes, Storage storage) {
    updateLastAccess();
    openIfClosed(storage);
    var searcher = searcher(storage);
    try {
      var reader = searcher.getIndexReader();

      // we subtract metadata document added during open
      return changes == null ? reader.numDocs() - 1 : reader.numDocs() + changes.numDocs() - 1;
    } finally {

      release(storage, searcher);
    }
  }

  @Override
  public LuceneTxChanges buildTxChanges() throws IOException {
    if (isCollectionDelete()) {
      return new LuceneTxChangesMultiRid(
          this, createIndexWriter(new RAMDirectory()), createIndexWriter(new RAMDirectory()));
    } else {
      return new LuceneTxChangesSingleRid(
          this, createIndexWriter(new RAMDirectory()), createIndexWriter(new RAMDirectory()));
    }
  }

  @Override
  public Query deleteQuery(Storage storage, Object key, Identifiable value) {
    updateLastAccess();
    openIfClosed(storage);

    return LuceneIndexType.createDeleteQuery(value, indexDefinition.getProperties(), key);
  }

  @Override
  public void load(IndexEngineData data) {
  }

  @Override
  public void clear(Storage storage, AtomicOperation atomicOperation) {
    updateLastAccess();
    openIfClosed(storage);
    try {
      reopenToken = indexWriter.deleteAll();
    } catch (IOException e) {
      LogManager.instance().error(this, "Error on clearing Lucene index", e);
    }
  }

  @Override
  public synchronized void close() {
    doClose(false);
  }

  private void doClose(boolean onDelete) {
    if (closed.get()) {
      return;
    }

    try {
      cancelCommitTask();

      closeNRT();

      closeSearchManager();

      commitAndCloseWriter();

      if (!onDelete) {
        directory.getDirectory().close();
      }
    } catch (Exception e) {
      LogManager.instance().error(this, "Error on closing Lucene index", e);
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer) {
    throw new UnsupportedOperationException("Cannot iterate over a lucene index");
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(
      IndexEngineValuesTransformer valuesTransformer) {
    throw new UnsupportedOperationException("Cannot iterate over a lucene index");
  }

  @Override
  public Stream<Object> keyStream() {
    throw new UnsupportedOperationException("Cannot iterate over a lucene index");
  }

  @Override
  public long size(Storage storage, final IndexEngineValuesTransformer transformer) {
    return sizeInTx(null, storage);
  }

  @Override
  public void release(Storage storage, IndexSearcher searcher) {
    updateLastAccess();
    openIfClosed(storage);

    try {
      searcherManager.release(searcher);
    } catch (IOException e) {
      LogManager.instance().error(this, "Error on releasing index searcher  of Lucene index", e);
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean acquireAtomicExclusiveLock() {
    return true; // do nothing
  }

  @Override
  public String getIndexNameByKey(final Object key) {
    return name;
  }

  @Override
  public synchronized void freeze(DatabaseSessionInternal db, boolean throwException) {
    try {
      closeNRT();
      cancelCommitTask();
      commitAndCloseWriter();
    } catch (IOException e) {
      LogManager.instance().error(this, "Error on freezing Lucene index:: " + indexName(), e);
    }
  }

  @Override
  public void release(DatabaseSessionInternal db) {
    try {
      close();
      reOpen(db.getStorage());
    } catch (IOException e) {
      LogManager.instance().error(this, "Error on releasing Lucene index:: " + indexName(), e);
    }
  }
}
