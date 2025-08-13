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

package com.jetbrains.youtrackdb.internal.lucene.tests;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneInsertUpdateSingleDocumentTransactionTest extends LuceneBaseTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();

    var oClass = schema.createClass("City");
    oClass.createProperty("name", PropertyType.STRING);
    //noinspection EmptyTryBlock
    try (var command =
        session.execute("create index City.name on City (name) FULLTEXT ENGINE LUCENE")) {
    }
  }

  @Test
  public void testInsertUpdateTransactionWithIndex() {

    Schema schema = session.getMetadata().getSchema();
    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "");
    var doc1 = ((EntityImpl) session.newEntity("City"));
    doc1.setProperty("name", "");
    doc = doc;
    doc1 = doc1;
    session.commit();
    session.begin();
    doc = session.load(doc.getIdentity());
    doc1 = session.load(doc1.getIdentity());
    doc.setProperty("name", "Rome");
    doc1.setProperty("name", "Rome");
    session.commit();

    var indexManager = session.getSharedContext().getIndexManager();
    var idx = indexManager.getIndex("City.name");
    Collection<?> coll;
    try (var stream = idx.getRids(session, "Rome")) {
      coll = stream.toList();
    }

    session.begin();
    Assert.assertEquals(2, coll.size());
    Assert.assertEquals(2, idx.size(session));
    session.commit();
  }
}
