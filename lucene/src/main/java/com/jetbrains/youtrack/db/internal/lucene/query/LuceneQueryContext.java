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

package com.jetbrains.youtrack.db.internal.lucene.query;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.lucene.exception.LuceneIndexException;
import com.jetbrains.youtrack.db.internal.lucene.tx.LuceneTxChanges;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.highlight.TextFragment;

/**
 *
 */
public class LuceneQueryContext {

  private final CommandContext context;
  private final IndexSearcher searcher;
  private final Query query;
  private final Sort sort;
  private Optional<LuceneTxChanges> changes;
  private final HashMap<String, TextFragment[]> fragments;

  public LuceneQueryContext(
      final CommandContext context, final IndexSearcher searcher, final Query query) {
    this(context, searcher, query, Collections.emptyList());
  }

  public LuceneQueryContext(
      final CommandContext context,
      final IndexSearcher searcher,
      final Query query,
      final List<SortField> sortFields) {
    this.context = context;
    this.searcher = searcher;
    this.query = query;
    if (sortFields.isEmpty()) {
      sort = null;
    } else {
      sort = new Sort(sortFields.toArray(new SortField[]{}));
    }
    changes = Optional.empty();
    fragments = new HashMap<>();
  }

  public boolean isInTx() {
    return changes.isPresent();
  }

  public LuceneQueryContext withChanges(final LuceneTxChanges changes) {
    this.changes = Optional.ofNullable(changes);
    return this;
  }

  public void addHighlightFragment(
      final String field, final TextFragment[] fieldFragment) {
    fragments.put(field, fieldFragment);
  }

  public CommandContext getContext() {
    return context;
  }

  public Query getQuery() {
    return query;
  }

  public Optional<LuceneTxChanges> getChanges() {
    return changes;
  }

  public Sort getSort() {
    return sort;
  }

  public IndexSearcher getSearcher() {
    return changes.map(c -> new IndexSearcher(multiReader(c))).orElse(searcher);
  }

  private MultiReader multiReader(final LuceneTxChanges luceneTxChanges) {
    try {
      return new MultiReader(
          searcher.getIndexReader(), luceneTxChanges.searcher().getIndexReader());
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new LuceneIndexException("unable to create reader on changes"), e,
          context.getDatabaseSession());
    }
  }

  public long deletedDocs(final Query query) {
    return changes.map(c -> c.deletedDocs(query)).orElse(0L);
  }

  public boolean isUpdated(final Document doc, final Object key, final Identifiable value) {
    return changes.map(c -> c.isUpdated(doc, key, value)).orElse(false);
  }

  public boolean isDeleted(Storage storage, final Document doc, final Object key,
      final Identifiable value) {
    return changes.map(c -> c.isDeleted(storage, doc, key, value)).orElse(false);
  }

  public Map<String, TextFragment[]> getFragments() {
    return fragments;
  }
}
