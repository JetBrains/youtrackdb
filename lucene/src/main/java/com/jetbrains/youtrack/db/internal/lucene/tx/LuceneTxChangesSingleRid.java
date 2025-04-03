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
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.lucene.builder.LuceneIndexType;
import com.jetbrains.youtrack.db.internal.lucene.engine.LuceneIndexEngine;
import com.jetbrains.youtrack.db.internal.lucene.exception.LuceneIndexException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;

/**
 *
 */
public class LuceneTxChangesSingleRid extends LuceneTxChangesAbstract {

  private final Set<String> deleted = new HashSet<String>();
  private final Set<String> updated = new HashSet<String>();
  private final Set<Document> deletedDocs = new HashSet<Document>();

  public LuceneTxChangesSingleRid(
      final LuceneIndexEngine engine, final IndexWriter writer, final IndexWriter deletedIdx) {
    super(engine, writer, deletedIdx);
  }

  public void put(final Object key, final Identifiable value, final Document doc) {
    if (deleted.remove(value.getIdentity().toString())) {
      doc.add(LuceneIndexType.createField(TMP, value.getIdentity().toString(), Field.Store.YES));
      updated.add(value.getIdentity().toString());
    }
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
      if (value == null) {
        writer.deleteDocuments(engine.deleteQuery(session.getStorage(), key, value));
      } else if (((RecordId) value.getIdentity()).isTemporary()) {
        writer.deleteDocuments(engine.deleteQuery(session.getStorage(), key, value));
      } else {
        deleted.add(value.getIdentity().toString());
        var doc = engine.buildDocument(session, key, value);
        deletedDocs.add(doc);
        deletedIdx.addDocument(doc);
      }
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new LuceneIndexException(
              "Error while deleting entities in transaction from lucene index"),
          e, (String) null);
    }
  }

  public long numDocs() {
    return searcher().getIndexReader().numDocs() - deleted.size() - updated.size();
  }

  public Set<Document> getDeletedDocs() {
    return deletedDocs;
  }

  public boolean isDeleted(Storage storage, Document document, Object key, Identifiable value) {
    return deleted.contains(value.getIdentity().toString());
  }

  public boolean isUpdated(Document document, Object key, Identifiable value) {
    return updated.contains(value.getIdentity().toString());
  }
}
