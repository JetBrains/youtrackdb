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

package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneTransactionEmbeddedQueryTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {
    final var c1 = session.createVertexClass("C1");
    c1.createProperty("p1", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    c1.createIndex("C1.p1", "FULLTEXT", null, null, "LUCENE", new String[]{"p1"});
  }

  @Test
  public void testRollback() {
    session.begin();
    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.newEmbeddedList("p1", new String[]{"abc"});

    var query = "select from C1 where search_class( \"abc\")=true ";

    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
    }
    session.rollback();

    query = "select from C1 where search_class( \"abc\")=true  ";
    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(0);
    }
  }

  @Test
  public void txRemoveTest() {
    session.begin();

    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.newEmbeddedList("p1", new String[]{"abc"});

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");

    var query = "select from C1 where search_class( \"abc\")=true";
    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
      Assert.assertEquals(1, index.size(session));
    }
    session.commit();

    session.begin();
    try (var vertices = session.execute(query)) {

      assertThat(vertices).hasSize(1);
      Assert.assertEquals(1, index.size(session));
    }

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    session.delete(doc);

    try (var vertices = session.execute(query)) {

      Collection<RID> coll;
      try (var stream = index.getRids(session, "abc")) {
        coll = stream.toList();
      }

      assertThat(vertices).hasSize(0);
      Assert.assertEquals(0, coll.size());

      var iterator = coll.iterator();
      var i = 0;
      while (iterator.hasNext()) {
        iterator.next();
        i++;
      }
      Assert.assertEquals(0, i);
      Assert.assertEquals(0, index.size(session));
    }

    session.rollback();

    session.begin();
    try (var vertices = session.execute(query)) {

      assertThat(vertices).hasSize(1);

      Assert.assertEquals(1, index.size(session));
    }
    session.commit();
  }

  @Test
  @Ignore
  public void txUpdateTest() {

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");

    Assert.assertEquals(0, index.size(session));

    session.begin();

    var doc = ((EntityImpl) session.newEntity("c1"));
    doc.setProperty("p1", new String[]{"update removed", "update fixed"});

    var query = "select from C1 where search_class(\"update\")=true ";
    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
      Assert.assertEquals(2, index.size(session));
    }
    session.commit();

    Collection coll;
    try (var vertices = session.execute(query)) {
      try (var stream = index.getRids(session, "update")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);
      Assert.assertEquals(2, coll.size());
      Assert.assertEquals(2, index.size(session));
    }
    session.begin();

    // select in transaction while updating
    Collection p1 = doc.getProperty("p1");
    p1.remove("update removed");

    try (var vertices = session.execute(query)) {
      try (var stream = index.getRids(session, "update")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);
      Assert.assertEquals(1, coll.size());
      Assert.assertEquals(1, index.size(session));
    }

    try (var vertices = session.execute(query)) {
      try (var stream = index.getRids(session, "update")) {
        coll = stream.collect(Collectors.toList());
      }
      Assert.assertEquals(1, coll.size());
      assertThat(vertices).hasSize(1);
    }

    session.rollback();

    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
    }

    Assert.assertEquals(2, index.size(session));
  }

  @Test
  public void txUpdateTestComplex() {

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");

    session.begin();
    Assert.assertEquals(0, index.size(session));

    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.newEmbeddedList("p1", new String[]{"abc"});

    var doc1 = ((EntityImpl) session.newVertex("c1"));
    doc1.newEmbeddedList("p1", new String[]{"abc"});

    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.newEmbeddedList("p1", new String[]{"removed"});

    var query = "select from C1 where p1 lucene \"abc\"";

    try (var vertices = session.query(query)) {
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
      Assert.assertNotNull(rid);
      Assert.assertEquals(doc1.getIdentity().toString(), rid.getIdentity().toString());
      Assert.assertEquals(2, index.size(session));
    }

    query = "select from C1 where p1 lucene \"removed\" ";
    try (var vertices = session.query(query)) {
      Collection coll;
      try (var stream = index.getRids(session, "removed")) {
        coll = stream.collect(Collectors.toList());
      }

      Assert.assertEquals(1, vertices.stream().count());
      Assert.assertEquals(1, coll.size());

      session.rollback();
    }

    query = "select from C1 where p1 lucene \"abc\" ";

    try (var vertices = session.query(query)) {

      Assert.assertEquals(2, vertices.stream().count());

      Assert.assertEquals(2, index.size(session));
    }
  }
}
