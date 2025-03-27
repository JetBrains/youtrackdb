/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrack.db.internal.lucene.collections;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngine;
import com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngineAbstract;
import com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngineUtils;
import com.jetbrains.youtrack.db.internal.lucene.exception.LuceneIndexException;
import com.jetbrains.youtrack.db.internal.lucene.query.LuceneQueryContext;
import com.jetbrains.youtrack.db.internal.lucene.tx.LuceneTxChangesAbstract;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Formatter;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryTermScorer;
import org.apache.lucene.search.highlight.Scorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.TokenSources;

/**
 *
 */
public class LuceneResultSet implements Set<Identifiable> {

  private static final Integer PAGE_SIZE = 10000;
  private Query query;
  private LuceneIndexEngine engine;
  private LuceneQueryContext queryContext;
  private String indexName;
  private Highlighter highlighter;
  private List<String> highlighted;
  private int maxNumFragments;
  private TopDocs topDocs;
  private long deletedMatchCount = 0;

  private boolean closed = false;
  private final DatabaseSessionInternal session;

  protected LuceneResultSet(DatabaseSessionInternal session) {
    this.session = session;
  }

  public LuceneResultSet(
      DatabaseSessionInternal session, final LuceneIndexEngine engine,
      final LuceneQueryContext queryContext,
      final Map<String, ?> metadata) {
    this.engine = engine;
    this.queryContext = queryContext;
    this.query = queryContext.getQuery();
    this.indexName = engine.indexName();
    this.session = session;

    fetchFirstBatch();
    deletedMatchCount = calculateDeletedMatch();

    final Map<String, Object> highlight =
        Optional.ofNullable((Map) metadata.get("highlight")).orElse(Collections.emptyMap());

    highlighted =
        Optional.ofNullable((List<String>) highlight.get("fields")).orElse(Collections.emptyList());

    final var startElement = (String) Optional.ofNullable(highlight.get("start")).orElse("<B>");

    final var endElement = (String) Optional.ofNullable(highlight.get("end")).orElse("</B>");

    final Scorer scorer = new QueryTermScorer(queryContext.getQuery());
    final Formatter formatter = new SimpleHTMLFormatter(startElement, endElement);
    highlighter = new Highlighter(formatter, scorer);

    maxNumFragments = (int) Optional.ofNullable(highlight.get("maxNumFragments")).orElse(2);
  }

  protected void fetchFirstBatch() {
    try {
      final var searcher = queryContext.getSearcher();
      if (queryContext.getSort() == null) {
        topDocs = searcher.search(query, PAGE_SIZE);
      } else {
        topDocs = searcher.search(query, PAGE_SIZE, queryContext.getSort());
      }
    } catch (final IOException e) {
      LogManager.instance()
          .error(this, "Error on fetching entity by query '%s' to Lucene index", e, query);
    }
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public boolean contains(Object o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object[] toArray() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean add(Identifiable oIdentifiable) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean remove(Object o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean addAll(Collection<? extends Identifiable> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  public void sendLookupTime(CommandContext commandContext, long start) {
    LuceneIndexEngineUtils.sendLookupTime(indexName, commandContext, topDocs, -1, start);
  }

  protected long calculateDeletedMatch() {
    return queryContext.deletedDocs(query);
  }

  @Override
  public int size() {
    return (int) Math.max(0, topDocs.totalHits - deletedMatchCount);
  }

  @Override
  public Iterator<Identifiable> iterator() {
    return new LuceneResultSetIteratorTx();
  }

  private class LuceneResultSetIteratorTx implements Iterator<Identifiable> {

    private ScoreDoc[] scoreDocs;
    private int index;
    private int localIndex;
    private final long totalHits;

    public LuceneResultSetIteratorTx() {
      totalHits = topDocs.totalHits;
      index = 0;
      localIndex = 0;
      scoreDocs = topDocs.scoreDocs;
      LuceneIndexEngineUtils.sendTotalHits(
          indexName, queryContext.getContext(), topDocs.totalHits - deletedMatchCount);
    }

    @Override
    public boolean hasNext() {
      assert session.assertIfNotActive();
      final var hasNext = index < (totalHits - deletedMatchCount);
      if (!hasNext && !closed) {
        final var searcher = queryContext.getSearcher();
        if (searcher.getIndexReader().getRefCount() > 1) {
          assert session.assertIfNotActive();
          engine.release(session.getStorage(), searcher);
          closed = true;
        }
      }
      return hasNext;
    }

    @Override
    public Identifiable next() {
      assert session.assertIfNotActive();
      var storage = session.getStorage();
      ScoreDoc scoreDoc;
      ContextualRecordId res;
      Document doc;
      do {
        scoreDoc = fetchNext();
        doc = toDocument(scoreDoc);

        res = toRecordId(doc, scoreDoc);
      } while (isToSkip(storage, res, doc));
      index++;
      return res;
    }

    protected ScoreDoc fetchNext() {
      if (localIndex == scoreDocs.length) {
        localIndex = 0;
        fetchMoreResult();
      }
      final var score = scoreDocs[localIndex++];
      return score;
    }

    @Nullable
    private Document toDocument(final ScoreDoc score) {
      try {
        return queryContext.getSearcher().doc(score.doc);
      } catch (final IOException e) {
        LogManager.instance().error(this, "Error during conversion to entity", e);
        return null;
      }
    }

    private ContextualRecordId toRecordId(final Document doc, final ScoreDoc score) {
      final var rId = doc.get(LuceneIndexEngineAbstract.RID);
      final var res = new ContextualRecordId(rId);

      final var indexReader = queryContext.getSearcher().getIndexReader();
      try {
        for (final var field : highlighted) {
          final var text = doc.get(field);
          if (text != null) {
            var tokenStream =
                TokenSources.getAnyTokenStream(
                    indexReader, score.doc, field, doc, engine.indexAnalyzer());
            var frag =
                highlighter.getBestTextFragments(tokenStream, text, true, maxNumFragments);
            queryContext.addHighlightFragment(field, frag);
          }
        }
        engine.onRecordAddedToResultSet(queryContext, res, doc, score);
        return res;
      } catch (IOException | InvalidTokenOffsetsException e) {
        throw BaseException.wrapException(new LuceneIndexException("error while highlighting"), e,
            session);
      }
    }

    private boolean isToSkip(Storage storage, final ContextualRecordId recordId,
        final Document doc) {
      return isDeleted(storage, recordId, doc) || isUpdatedDiskMatch(recordId, doc) ;
    }

    private void fetchMoreResult() {
      TopDocs topDocs = null;
      try {
        final var searcher = queryContext.getSearcher();
        if (queryContext.getSort() == null) {
          topDocs = searcher.searchAfter(scoreDocs[scoreDocs.length - 1], query, PAGE_SIZE);
        } else {
          topDocs =
              searcher.searchAfter(
                  scoreDocs[scoreDocs.length - 1], query, PAGE_SIZE, queryContext.getSort());
        }
        scoreDocs = topDocs.scoreDocs;
      } catch (final IOException e) {
        LogManager.instance()
            .error(this, "Error on fetching entity by query '%s' to Lucene index", e, query);
      }
    }

    private boolean isDeleted(Storage storage, Identifiable value, Document doc) {
      return queryContext.isDeleted(storage, doc, null, value);
    }

    private boolean isUpdatedDiskMatch(Identifiable value, Document doc) {
      return isUpdated(value) && !isTempMatch(doc);
    }

    private boolean isUpdated(Identifiable value) {
      return queryContext.isUpdated(null, null, value);
    }

    private boolean isTempMatch(Document doc) {
      return doc.get(LuceneTxChangesAbstract.TMP) != null;
    }

    @Override
    public void remove() {
      // TODO: something to be done here?
    }
  }
}
