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

package com.jetbrains.youtrackdb.internal.lucene.test;

import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneTransactionQueryTest extends BaseLuceneTest {

  @Before
  public void init() {

    final var c1 = session.createVertexClass("C1");
    c1.createProperty("p1", PropertyType.STRING);
    c1.createIndex("C1.p1", "FULLTEXT", null, null, "LUCENE", new String[]{"p1"});
  }

  @Test
  public void testRollback() {
    session.begin();
    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "abc");

    var vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    Assert.assertEquals(1, vertices.stream().count());
    session.rollback();

    vertices = session.query("select from C1 where p1 lucene \"abc\" ");
    Assert.assertEquals(0, vertices.stream().count());
  }

  @Test
  public void txRemoveTest() {
    session.begin();

    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "abc");

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");

    var vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(1, index.size(session));
    session.commit();

    session.begin();
    vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    var result = vertices.next();

    Assert.assertFalse(vertices.hasNext());
    Assert.assertEquals(1, index.size(session));

    doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "abc");

    vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    Collection coll;
    try (var rids = index.getRids(session, "abc")) {
      coll = rids.collect(Collectors.toList());
    }

    Assert.assertEquals(2, vertices.stream().count());
    Assert.assertEquals(2, coll.size());

    session.delete(result.asRecord());

    vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    try (var rids = index.getRids(session, "abc")) {
      coll = rids.collect(Collectors.toList());
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

    doc.delete();

    vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    try (var rids = index.getRids(session, "abc")) {
      coll = rids.collect(Collectors.toList());
    }

    Assert.assertEquals(0, vertices.stream().count());
    Assert.assertEquals(0, coll.size());
    session.rollback();

    session.begin();
    vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(1, index.size(session));
    session.commit();
  }

  @Test
  public void txUpdateTest() {

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");
    var c1 = session.getMetadata().getSlowMutableSchema().getClassInternal("C1");
    c1.truncate();

    session.begin();
    Assert.assertEquals(0, index.size(session));

    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "update");

    var vertices = session.query("select from C1 where p1 lucene \"update\" ");

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(1, index.size(session));

    session.commit();

    session.begin();
    vertices = session.query("select from C1 where p1 lucene \"update\" ");

    Collection coll;
    try (var stream = index.getRids(session, "update")) {
      coll = stream.collect(Collectors.toList());
    }

    var res = vertices.next();
    Assert.assertFalse(vertices.hasNext());
    Assert.assertEquals(1, coll.size());
    Assert.assertEquals(1, index.size(session));
    session.commit();

    session.begin();

    var identifiable = res.asEntity();
    var activeTx = session.getActiveTransaction();
    var record = activeTx.<Entity>load(identifiable);
    record.setProperty("p1", "removed");

    vertices = session.query("select from C1 where p1 lucene \"update\" ");
    try (var stream = index.getRids(session, "update")) {
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

    Assert.assertEquals(1, index.size(session));

    vertices = session.query("select from C1 where p1 lucene \"removed\"");
    try (var stream = index.getRids(session, "removed")) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(1, vertices.stream().count());
    Assert.assertEquals(1, coll.size());

    session.rollback();

    vertices = session.query("select from C1 where p1 lucene \"update\" ");

    Assert.assertEquals(1, vertices.stream().count());

    Assert.assertEquals(1, index.size(session));
  }

  @Test
  public void txUpdateTestComplex() {

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");
    var c1 = session.getMetadata().getSlowMutableSchema().getClassInternal("C1");
    c1.truncate();

    session.begin();
    Assert.assertEquals(0, index.size(session));

    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "abc");

    var doc1 = ((EntityImpl) session.newVertex("c1"));
    doc1.setProperty("p1", "abc");

    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("p1", "removed");

    var vertices = session.query("select from C1 where p1 lucene \"abc\"");
    Collection coll;
    try (var stream = index.getRids(session, "abc")) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(1, vertices.stream().count());
    Assert.assertEquals(1, coll.size());

    var iterator = coll.iterator();
    var i = 0;
    RecordIdInternal rid = null;
    while (iterator.hasNext()) {
      rid = (RecordIdInternal) iterator.next();
      i++;
    }

    Assert.assertEquals(1, i);
    Assert.assertNotNull(doc1);
    Assert.assertNotNull(rid);
    Assert.assertEquals(doc1.getIdentity().toString(), rid.getIdentity().toString());
    Assert.assertEquals(2, index.size(session));

    vertices = session.query("select from C1 where p1 lucene \"removed\" ");
    try (var stream = index.getRids(session, "removed")) {
      coll = stream.collect(Collectors.toList());
    }

    Assert.assertEquals(1, vertices.stream().count());
    Assert.assertEquals(1, coll.size());

    session.rollback();

    vertices = session.query("select from C1 where p1 lucene \"abc\" ");

    Assert.assertEquals(2, vertices.stream().count());

    Assert.assertEquals(2, index.size(session));
  }
}
