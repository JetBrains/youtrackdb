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

package com.jetbrains.youtrack.db.internal.lucene.engine;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngine;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.FreezableStorageComponent;
import com.jetbrains.youtrack.db.internal.lucene.query.LuceneQueryContext;
import com.jetbrains.youtrack.db.internal.lucene.tx.LuceneTxChanges;
import java.io.IOException;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;

/**
 *
 */
public interface LuceneIndexEngine extends IndexEngine, FreezableStorageComponent {

  String indexName();

  void onRecordAddedToResultSet(
      LuceneQueryContext queryContext, ContextualRecordId recordId, Document ret,
      ScoreDoc score);

  Document buildDocument(DatabaseSessionInternal session, Object key, Identifiable value);

  Query buildQuery(Object query, DatabaseSessionInternal session);

  Analyzer indexAnalyzer();

  Analyzer queryAnalyzer();

  boolean remove(Storage storage, Object key, Identifiable value);

  boolean remove(Storage storage, Object key);

  IndexSearcher searcher(Storage storage);

  void release(Storage storage, IndexSearcher searcher);

  Set<Identifiable> getInTx(DatabaseSessionInternal session, Object key,
      LuceneTxChanges changes);

  long sizeInTx(LuceneTxChanges changes, Storage storage);

  LuceneTxChanges buildTxChanges() throws IOException;

  Query deleteQuery(Storage storage, Object key, Identifiable value);

  boolean isCollectionIndex();
}
