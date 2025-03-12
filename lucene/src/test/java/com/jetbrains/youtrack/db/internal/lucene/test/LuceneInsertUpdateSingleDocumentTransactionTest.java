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

package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneInsertUpdateSingleDocumentTransactionTest extends BaseLuceneTest {

  public LuceneInsertUpdateSingleDocumentTransactionTest() {
    super();
  }

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();

    var oClass = schema.createClass("City");
    oClass.createProperty("name", PropertyType.STRING);
    session.command("create index City.name on City (name) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void testInsertUpdateTransactionWithIndex() {

    session.close();
    session = openDatabase();
    Schema schema = session.getMetadata().getSchema();
    session.begin();
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.field("name", "");
    var doc1 = ((EntityImpl) session.newEntity("City"));
    doc1.field("name", "");
    doc = doc;
    doc1 = doc1;
    session.commit();
    session.begin();
    doc = session.load(doc.getIdentity());
    doc1 = session.load(doc1.getIdentity());

    doc.field("name", "Rome");
    doc1.field("name", "Rome");
    session.commit();
    var idx = session.getClassInternal("City").getClassIndex(session, "City.name");
    Collection<?> coll;
    try (var stream = idx.getInternal().getRids(session, "Rome")) {
      coll = stream.collect(Collectors.toList());
    }

    session.begin();
    Assert.assertEquals(coll.size(), 2);
    Assert.assertEquals(2, idx.getInternal().size(session));
    session.commit();
  }
}
