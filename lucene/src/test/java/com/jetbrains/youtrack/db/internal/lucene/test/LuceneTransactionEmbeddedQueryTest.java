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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.lucene.tests.LuceneBaseTest;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneTransactionEmbeddedQueryTest extends LuceneBaseTest {
  @Test
  public void testRollback() {
    createSchema(session);

    var doc = ((EntityImpl) session.newEntity("c1"));
    doc.setProperty("p1", new String[]{"abc"});
    session.begin();

    var query = "select from C1 where p1 lucene \"abc\" ";
    var vertices = session.query(query);

    Assert.assertEquals(1, vertices.stream().count());
    session.rollback();

    query = "select from C1 where p1 lucene \"abc\" ";
    vertices = session.query(query);
    Assert.assertEquals(0, vertices.stream().count());
  }

  private static void createSchema(DatabaseSession db) {
    final var c1 = db.getSchema().createVertexClass("C1");
    c1.createProperty("p1", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    c1.createIndex("C1.p1", "FULLTEXT", null, null, "LUCENE", new String[]{"p1"});
  }

  @Test
  public void txRemoveTest() {
    createSchema(session);
    session.begin();

    var doc = ((EntityImpl) session.newEntity("c1"));
    doc.setProperty("p1", new String[]{"abc"});

    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "C1.p1");

    var query = "select from C1 where p1 lucene \"abc\" ";
    var vertices = session.query(query);

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(1, index.size(session));
    session.commit();

    query = "select from C1 where p1 lucene \"abc\" ";
    vertices = session.query(query);

    var res = vertices.next();
    session.begin();
    Assert.assertEquals(1, index.size(session));

    session.delete(res.asEntity());

    query = "select from C1 where p1 lucene \"abc\" ";
    vertices = session.query(query);

    Collection coll;
    try (var stream = index.getRids(session, "abc")) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(0, vertices.stream().count());
    Assert.assertEquals(0, coll.size());

    var iterator = coll.iterator();
    var i = 0;
    while (iterator.hasNext()) {
      iterator.next();
      i++;
    }
    Assert.assertEquals(0, i);
    Assert.assertEquals(0, index.size(session));

    session.rollback();

    query = "select from C1 where p1 lucene \"abc\" ";
    vertices = session.query(query);

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(1, index.size(session));
  }

  @Test
  public void txUpdateTest() {
    createSchema(session);
    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "C1.p1");

    session.begin();
    Assert.assertEquals(0, index.size(session));

    var doc = ((EntityImpl) session.newEntity("c1"));
    doc.setProperty("p1", new String[]{"update removed", "update fixed"});

    var query = "select from C1 where p1 lucene \"update\" ";
    var vertices = session.query(query);

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(2, index.size(session));

    session.commit();

    query = "select from C1 where p1 lucene \"update\" ";
    //noinspection deprecation
    vertices = session.query(query);

    Collection coll;
    try (final var stream = index.getRids(session, "update")) {
      coll = stream.collect(Collectors.toList());
    }

    var resultRecord = vertices.next();
    Assert.assertEquals(2, coll.size());
    Assert.assertEquals(2, index.size(session));

    session.begin();

    // select in transaction while updating
    Entity identifiable = resultRecord.asEntity();
    var activeTx = session.getActiveTransaction();
    var record = activeTx.<Entity>load(identifiable);
    Collection p1 = record.getProperty("p1");
    p1.remove("update removed");

    query = "select from C1 where p1 lucene \"update\" ";
    vertices = session.query(query);
    try (var stream = index.getRids(session, "update")) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(1, vertices.stream().count());
    Assert.assertEquals(1, coll.size());

    var iterator = coll.iterator();
    var i = 0;
    while (iterator.hasNext()) {
      iterator.next();
      i++;
    }
    Assert.assertEquals(1, i);

    Assert.assertEquals(1, index.size(session));

    query = "select from C1 where p1 lucene \"update\"";
    vertices = session.query(query);

    try (var stream = index.getRids(session, "update")) {
      coll = stream.collect(Collectors.toList());
    }
    Assert.assertEquals(1, coll.size());

    Assert.assertEquals(1, vertices.stream().count());

    session.rollback();

    query = "select from C1 where p1 lucene \"update\" ";
    vertices = session.query(query);

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(2, index.size(session));
  }

  @Test
  public void txUpdateTestComplex() {
    createSchema(session);
    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "C1.p1");

    Assert.assertEquals(0, index.size(session));

    session.begin();

    var doc = ((EntityImpl) session.newEntity("c1"));
    doc.setProperty("p1", new String[]{"abc"});

    var doc1 = ((EntityImpl) session.newEntity("c1"));
    doc1.setProperty("p1", new String[]{"abc"});

    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("p1", new String[]{"removed"});

    var query = "select from C1 where p1 lucene \"abc\"";
    var vertices = session.query(query);
    Collection coll;
    try (var stream = index.getRids(session, "abc")) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(1, vertices.stream().count());
    Assert.assertEquals(1, coll.size());

    var iterator = coll.iterator();
    var i = 0;
    RecordId rid = null;
    while (iterator.hasNext()) {
      rid = (RecordId) iterator.next();
      i++;
    }

    Assert.assertEquals(1, i);
    Assert.assertNotNull(doc1);
    Assert.assertNotNull(rid);
    Assert.assertEquals(doc1.getIdentity().toString(), rid.getIdentity().toString());
    Assert.assertEquals(2, index.size(session));

    query = "select from C1 where p1 lucene \"removed\" ";
    vertices = session.query(query);
    try (var stream = index.getRids(session, "removed")) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(1, vertices.stream().count());
    Assert.assertEquals(1, coll.size());

    session.rollback();

    query = "select from C1 where p1 lucene \"abc\" ";
    vertices = session.query(query);

    Assert.assertEquals(2, vertices.stream().count());

    Assert.assertEquals(2, index.size(session));
  }
}
