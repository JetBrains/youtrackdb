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

package com.jetbrains.youtrack.db.internal.lucene.index;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngine;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;

public class LuceneFullTextIndex extends LuceneIndexNotUnique {

  public LuceneFullTextIndex(@Nullable RID identity,
      @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage) {
    super(identity, transaction, storage);
  }

  public LuceneFullTextIndex(@Nonnull Storage storage) {
    super(storage);
  }

  public Document buildDocument(DatabaseSessionInternal db, final Object key) {

    while (true) {
      try {
        return storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var indexEngine = (LuceneIndexEngine) engine;
              return indexEngine.buildDocument(db, key, null);
            });
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  public Query buildQuery(final Object query, DatabaseSessionInternal session) {
    while (true) {
      try {
        return storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var indexEngine = (LuceneIndexEngine) engine;
              return indexEngine.buildQuery(query, session);
            });
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  public Analyzer queryAnalyzer() {
    while (true) {
      try {
        return storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var indexEngine = (LuceneIndexEngine) engine;
              return indexEngine.queryAnalyzer();
            });
      } catch (final InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  public boolean isCollectionIndex() {
    while (true) {
      try {
        return storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var indexEngine = (LuceneIndexEngine) engine;
              return indexEngine.isCollectionIndex();
            });
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }

  public Analyzer indexAnalyzer() {
    while (true) {
      try {
        return storage.callIndexEngine(
            false,
            indexId,
            engine -> {
              var indexEngine = (LuceneIndexEngine) engine;
              return indexEngine.indexAnalyzer();
            });
      } catch (InvalidIndexEngineIdException e) {
        doReloadIndexEngine();
      }
    }
  }
}
