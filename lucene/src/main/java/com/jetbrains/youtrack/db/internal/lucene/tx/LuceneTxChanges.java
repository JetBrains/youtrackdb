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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import java.util.Collections;
import java.util.Set;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

/**
 *
 */
public interface LuceneTxChanges {

  void put(Object key, Identifiable value, Document doc);

  void remove(DatabaseSessionInternal session, Object key, Identifiable value);

  IndexSearcher searcher();

  default long numDocs() {
    return 0;
  }

  default Set<Document> getDeletedDocs() {
    return Collections.emptySet();
  }

  boolean isDeleted(Storage storage, Document document, Object key, Identifiable value);

  boolean isUpdated(Document document, Object key, Identifiable value);

  default long deletedDocs(Query query) {
    return 0;
  }
}
