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

package com.jetbrains.youtrack.db.internal.lucene.engine;

import static com.jetbrains.youtrack.db.internal.lucene.builder.LuceneQueryBuilder.EMPTY_METADATA;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.IndexEngineException;
import com.jetbrains.youtrack.db.internal.core.index.IndexKeyUpdater;
import com.jetbrains.youtrack.db.internal.core.index.IndexMetadata;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrack.db.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.lucene.builder.LuceneDocumentBuilder;
import com.jetbrains.youtrack.db.internal.lucene.builder.LuceneIndexType;
import com.jetbrains.youtrack.db.internal.lucene.builder.LuceneQueryBuilder;
import com.jetbrains.youtrack.db.internal.lucene.collections.LuceneCompositeKey;
import com.jetbrains.youtrack.db.internal.lucene.collections.LuceneIndexTransformer;
import com.jetbrains.youtrack.db.internal.lucene.collections.LuceneResultSet;
import com.jetbrains.youtrack.db.internal.lucene.query.LuceneKeyAndMetadata;
import com.jetbrains.youtrack.db.internal.lucene.query.LuceneQueryContext;
import com.jetbrains.youtrack.db.internal.lucene.tx.LuceneTxChanges;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;

public class LuceneFullTextIndexEngine extends LuceneIndexEngineAbstract {

  private final LuceneDocumentBuilder builder;
  private LuceneQueryBuilder queryBuilder;
  private final AtomicLong bonsayFileId = new AtomicLong(0);

  public LuceneFullTextIndexEngine(Storage storage, String idxName, int id) {
    super(id, storage, idxName);
    builder = new LuceneDocumentBuilder();
  }

  @Override
  public void init(DatabaseSessionInternal db, IndexMetadata im) {
    super.init(db, im);
    queryBuilder = new LuceneQueryBuilder(im.getMetadata());
  }

  @Override
  public IndexWriter createIndexWriter(Directory directory) throws IOException {

    var fc = new LuceneIndexWriterFactory();

    LogManager.instance().debug(this, "Creating Lucene index in '%s'...", directory);

    return fc.createIndexWriter(directory, metadata, indexAnalyzer());
  }

  @Override
  public void onRecordAddedToResultSet(
      final LuceneQueryContext queryContext,
      final ContextualRecordId recordId,
      final Document ret,
      final ScoreDoc score) {
    var data = new HashMap<String, Object>();

    final var frag = queryContext.getFragments();
    frag.forEach(
        (key, fragments) -> {
          final var hlField = new StringBuilder();
          for (final var fragment : fragments) {
            if ((fragment != null) && (fragment.getScore() > 0)) {
              hlField.append(fragment);
            }
          }
          data.put("$" + key + "_hl", hlField.toString());
        });
    data.put("$score", score.score);

    recordId.setContext(data);
  }

  @Override
  public boolean remove(Storage storage, final AtomicOperation atomicOperation,
      final Object key) {
    return remove(storage, key);
  }

  @Override
  public Object get(DatabaseSessionInternal db, final Object key) {
    return getInTx(db, key, null);
  }

  @Override
  public void update(
      DatabaseSessionInternal db, final AtomicOperation atomicOperation,
      final Object key,
      final IndexKeyUpdater<Object> updater) {
    put(db, atomicOperation, key, updater.update(null, bonsayFileId).getValue());
  }

  @Override
  public void put(DatabaseSessionInternal db, final AtomicOperation atomicOperation,
      final Object key, final Object value) {
    updateLastAccess();
    openIfClosed(db.getStorage());
    final var doc = buildDocument(db, key, (Identifiable) value);
    addDocument(doc);
  }

  @Override
  public boolean validatedPut(
      AtomicOperation atomicOperation,
      Object key,
      com.jetbrains.youtrack.db.api.record.RID value,
      IndexEngineValidator<Object, com.jetbrains.youtrack.db.api.record.RID> validator) {
    throw new UnsupportedOperationException(
        "Validated put is not supported by LuceneFullTextIndexEngine");
  }

  @Override
  public Stream<RawPair<Object, com.jetbrains.youtrack.db.api.record.RID>> iterateEntriesBetween(
      DatabaseSessionInternal db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return LuceneIndexTransformer.transformToStream((LuceneResultSet) get(db, rangeFrom),
        rangeFrom);
  }

  private Set<Identifiable> getResults(
      final Query query,
      final CommandContext context,
      final LuceneTxChanges changes,
      final Map<String, ?> metadata) {
    // sort
    final var fields = LuceneIndexEngineUtils.buildSortFields(metadata);
    var db = context.getDatabaseSession();
    final var luceneSearcher = searcher(db.getStorage());
    final var queryContext =
        new LuceneQueryContext(context, luceneSearcher, query, fields).withChanges(changes);
    return new LuceneResultSet(db, this, queryContext, metadata);
  }

  @Override
  public Stream<RawPair<Object, com.jetbrains.youtrack.db.api.record.RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return Stream.empty();
  }

  @Override
  public Stream<RawPair<Object, com.jetbrains.youtrack.db.api.record.RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return Stream.empty();
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return false;
  }

  @Override
  public Document buildDocument(DatabaseSessionInternal session, Object key,
      Identifiable value) {
    if (indexDefinition.isAutomatic()) {
      //      builder.newBuild(index, key, value);

      return builder.build(indexDefinition, key, value, collectionFields, metadata);
    } else {
      return putInManualindex(key, value);
    }
  }

  private static Document putInManualindex(Object key, Identifiable oIdentifiable) {
    var doc = new Document();
    doc.add(LuceneIndexType.createOldIdField(oIdentifiable));
    doc.add(LuceneIndexType.createIdField(oIdentifiable, key));

    if (key instanceof CompositeKey) {

      var keys = ((CompositeKey) key).getKeys();

      var k = 0;
      for (var o : keys) {
        doc.add(LuceneIndexType.createField("k" + k, o, Field.Store.YES));
        k++;
      }
    } else if (key instanceof Collection) {
      @SuppressWarnings("unchecked")
      var keys = (Collection<Object>) key;

      var k = 0;
      for (var o : keys) {
        doc.add(LuceneIndexType.createField("k" + k, o, Field.Store.YES));
        k++;
      }
    } else {
      doc.add(LuceneIndexType.createField("k0", key, Field.Store.NO));
    }
    return doc;
  }

  @Override
  public Query buildQuery(final Object maybeQuery, DatabaseSessionInternal session) {
    try {
      if (maybeQuery instanceof String) {
        return queryBuilder.query(indexDefinition, maybeQuery, EMPTY_METADATA,
            queryAnalyzer(), session);
      } else {
        var q = (LuceneKeyAndMetadata) maybeQuery;
        return queryBuilder.query(indexDefinition, q.key, q.metadata, queryAnalyzer(), session);
      }
    } catch (final ParseException e) {
      throw BaseException.wrapException(new IndexEngineException(null, "Error parsing query"), e,
          (String) null);
    }
  }

  @Override
  public Set<Identifiable> getInTx(DatabaseSessionInternal session, Object key,
      LuceneTxChanges changes) {
    updateLastAccess();
    openIfClosed(session.getStorage());
    try {
      if (key instanceof LuceneKeyAndMetadata q) {
        var query = queryBuilder.query(indexDefinition, q.key, q.metadata, queryAnalyzer(),
            session);

        var commandContext = q.key.getContext();
        return getResults(query, commandContext, changes, q.metadata);

      } else {
        var query = queryBuilder.query(indexDefinition, key, EMPTY_METADATA,
            queryAnalyzer(), session);

        CommandContext commandContext = null;
        if (key instanceof LuceneCompositeKey) {
          commandContext = ((LuceneCompositeKey) key).getContext();
        } else {
          commandContext = new BasicCommandContext();
          commandContext.setDatabaseSession(session);
        }
        return getResults(query, commandContext, changes, EMPTY_METADATA);
      }
    } catch (ParseException e) {
      throw BaseException.wrapException(
          new IndexEngineException(session.getDatabaseName(), "Error parsing lucene query"),
          e, session);
    }
  }
}
