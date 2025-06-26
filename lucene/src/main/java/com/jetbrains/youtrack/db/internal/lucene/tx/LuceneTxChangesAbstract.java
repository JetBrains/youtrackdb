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

package com.jetbrains.youtrack.db.internal.lucene.tx;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngine;
import com.jetbrains.youtrack.db.internal.lucene.exception.LuceneIndexException;
import java.io.IOException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

/**
 *
 */
public abstract class LuceneTxChangesAbstract implements LuceneTxChanges {

  public static final String TMP = "_tmp_rid";

  protected final LuceneIndexEngine engine;
  protected final IndexWriter writer;
  protected final IndexWriter deletedIdx;

  public LuceneTxChangesAbstract(
      final LuceneIndexEngine engine, final IndexWriter writer, final IndexWriter deletedIdx) {
    this.engine = engine;
    this.writer = writer;
    this.deletedIdx = deletedIdx;
  }

  public IndexSearcher searcher() {
    // TODO optimize
    try {
      return new IndexSearcher(DirectoryReader.open(writer, true, true));
    } catch (IOException e) {
      //      LogManager.instance().error(this, "Error during searcher index instantiation on new
      // documents", e);
      throw BaseException.wrapException(
          new LuceneIndexException("Error during searcher index instantiation on new entities"),
          e, (String) null);
    }
  }

  @Override
  public long deletedDocs(Query query) {
    try {
      final var indexSearcher =
          new IndexSearcher(DirectoryReader.open(deletedIdx, true, true));
      final var search = indexSearcher.search(query, Integer.MAX_VALUE);
      return search.totalHits;
    } catch (IOException e) {
      LogManager.instance()
          .error(this, "Error during searcher index instantiation on deleted entities ", e);
    }
    return 0;
  }
}
