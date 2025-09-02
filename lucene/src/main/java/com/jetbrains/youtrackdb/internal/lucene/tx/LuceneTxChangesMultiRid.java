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

package com.jetbrains.youtrackdb.internal.lucene.tx;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.lucene.engine.LuceneIndexEngine;
import com.jetbrains.youtrackdb.internal.lucene.exception.LuceneIndexException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.memory.MemoryIndex;

/**
 *
 */
public class LuceneTxChangesMultiRid extends LuceneTxChangesAbstract {

  private final Map<String, List<String>> deleted = new HashMap<String, List<String>>();
  private final Set<Document> deletedDocs = new HashSet<Document>();

  public LuceneTxChangesMultiRid(
      final LuceneIndexEngine engine, final IndexWriter writer, final IndexWriter deletedIdx) {
    super(engine, writer, deletedIdx);
  }

  public void put(final Object key, final Identifiable value, final Document doc) {
    try {
      writer.addDocument(doc);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new LuceneIndexException("unable to add entity to changes index"), e, (String) null);
    }
  }

  public void remove(DatabaseSessionInternal session, final Object key,
      final Identifiable value) {
    try {
      if (((RecordId)value.getIdentity()).isTemporary()) {
        writer.deleteDocuments(engine.deleteQuery(session.getStorage(), key, value));
      } else {
        deleted.putIfAbsent(value.getIdentity().toString(), new ArrayList<>());
        deleted.get(value.getIdentity().toString()).add(key.toString());

        final var doc = engine.buildDocument(session, key, value);
        deletedDocs.add(doc);
        deletedIdx.addDocument(doc);
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new LuceneIndexException(
              "Error while deleting entities in transaction from lucene index"),
          e, session);
    }
  }

  public long numDocs() {
    return searcher().getIndexReader().numDocs() - deletedDocs.size();
  }

  public Set<Document> getDeletedDocs() {
    return deletedDocs;
  }

  public boolean isDeleted(Storage storage, final Document document, final Object key,
      final Identifiable value) {
    var match = false;
    final var strings = deleted.get(value.getIdentity().toString());
    if (strings != null) {
      final var memoryIndex = new MemoryIndex();
      for (final var string : strings) {
        final var q = engine.deleteQuery(storage, string, value);
        memoryIndex.reset();
        for (final var field : document.getFields()) {
          memoryIndex.addField(field.name(), field.stringValue(), new KeywordAnalyzer());
        }
        match = match || (memoryIndex.search(q) > 0.0f);
      }
      return match;
    }
    return match;
  }

  // TODO is this valid?
  public boolean isUpdated(final Document document, final Object key, final Identifiable value) {
    return false;
  }
}
