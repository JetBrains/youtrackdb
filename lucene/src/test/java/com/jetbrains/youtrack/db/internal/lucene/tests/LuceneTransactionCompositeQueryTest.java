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

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Iterator;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneTransactionCompositeQueryTest extends LuceneBaseTest {

  @Before
  public void init() {

    final var c1 = session.createVertexClass("Foo");
    c1.createProperty("name", PropertyType.STRING);
    c1.createProperty("bar", PropertyType.STRING);
    c1.createIndex("Foo.bar", "FULLTEXT", null, null, "LUCENE", new String[]{"bar"});
    c1.createIndex("Foo.name", "NOTUNIQUE", null, null, "SBTREE", new String[]{"name"});
  }

  @Test
  public void testRollback() {

    var doc = ((EntityImpl) session.newEntity("Foo"));
    doc.field("name", "Test");
    doc.field("bar", "abc");
    session.begin();

    var query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"abc\") =true ";
    try (var vertices = session.command(query)) {

      assertThat(vertices).hasSize(1);
    }
    session.rollback();

    query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"abc\") = true ";
    try (var vertices = session.command(query)) {
      assertThat(vertices).hasSize(0);
    }
  }

  @Test
  public void txRemoveTest() {
    session.begin();

    var doc = ((EntityImpl) session.newEntity("Foo"));
    doc.field("name", "Test");
    doc.field("bar", "abc");

    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "Foo.bar");

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    session.delete(doc);

    var query = "select from Foo where name = 'Test' and  SEARCH_CLASS(\"abc\") = true ";
    try (var vertices = session.command(query)) {

      Collection coll;
      try (var stream = index.getRids(session, "abc")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(0);

      Assert.assertEquals(0, coll.size());

      Assert.assertEquals(0, index.size(session));
    }
    session.rollback();

    query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"abc\") = true ";
    try (var vertices = session.command(query)) {

      assertThat(vertices).hasSize(1);

      session.begin();
      Assert.assertEquals(1, index.size(session));
      session.commit();
    }
  }

  @Test
  public void txUpdateTest() {
    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "Foo.bar");
    var c1 = session.getMetadata().getSchema().getClassInternal("Foo");
    c1.truncate();

    session.begin();
    Assert.assertEquals(0, index.size(session));

    var doc = ((EntityImpl) session.newEntity("Foo"));
    doc.field("name", "Test");
    doc.field("bar", "abc");

    session.commit();

    session.begin();

    doc = session.bindToSession(doc);
    doc.field("bar", "removed");

    var query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"abc\") =true";
    Collection<?> coll;
    try (var vertices = session.query(query)) {
      try (var stream = index.getRids(session, "abc")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(0);
      Assert.assertEquals(0, coll.size());

      Iterator iterator = coll.iterator();
      var i = 0;
      while (iterator.hasNext()) {
        iterator.next();
        i++;
      }
      Assert.assertEquals(0, i);

      Assert.assertEquals(1, index.size(session));
    }
    query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"removed\")=true ";
    try (var vertices = session.query(query)) {
      try (var stream = index.getRids(session, "removed")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);
      Assert.assertEquals(1, coll.size());
    }

    session.rollback();

    query = "select from Foo where name = 'Test' and SEARCH_CLASS (\"abc\")=true ";
    try (var vertices = session.command(query)) {

      assertThat(vertices).hasSize(1);

      Assert.assertEquals(1, index.size(session));
    }
  }

  @Test
  public void txUpdateTestComplex() {
    var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "Foo.bar");
    var c1 = session.getMetadata().getSchema().getClassInternal("Foo");
    c1.truncate();

    session.begin();
    Assert.assertEquals(0, index.size(session));

    var doc = ((EntityImpl) session.newEntity("Foo"));
    doc.field("name", "Test");
    doc.field("bar", "abc");

    var doc1 = ((EntityImpl) session.newEntity("Foo"));
    doc1.field("name", "Test");
    doc1.field("bar", "abc");

    session.commit();

    session.begin();
    doc = session.bindToSession(doc);
    doc.field("bar", "removed");

    var query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"abc\")=true ";
    Collection coll;
    try (var vertices = session.query(query)) {
      try (var stream = index.getRids(session, "abc")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);
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
      Assert.assertEquals(rid.getIdentity().toString(), doc1.getIdentity().toString());
      Assert.assertEquals(2, index.size(session));
    }

    query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"removed\" )=true";
    try (var vertices = session.query(query)) {
      try (var stream = index.getRids(session, "removed")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);

      Assert.assertEquals(1, coll.size());
    }
    session.rollback();

    query = "select from Foo where name = 'Test' and SEARCH_CLASS(\"abc\")=true ";
    try (var vertices = session.query(query)) {
      assertThat(vertices).hasSize(2);
      Assert.assertEquals(2, index.size(session));
    }
  }
}
