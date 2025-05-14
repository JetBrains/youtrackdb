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

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneTransactionQueryTest extends LuceneBaseTest {

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


    var query = "select from C1 where search_fields(['p1'], 'abc' )=true ";

    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
    }

    session.rollback();

    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(0);
    }
  }

  @Test
  public void txRemoveTest() {
    session.begin();
    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "abc");

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");

    var query = "select from C1 where search_fields(['p1'], 'abc' )=true ";

    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
    }
    assertThat(index.size(session)).isEqualTo(1);

    session.commit();

    session.begin();
    List<Result> results;
    try (var vertices = session.execute(query)) {
      //noinspection resource
      results = vertices.stream().collect(Collectors.toList());
      assertThat(results).hasSize(1);
    }
    assertThat(index.size(session)).isEqualTo(1);

    doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "abc");

    Collection<Object> coll;
    try (var vertices = session.query(query)) {
      try (var stream = index.getRids(session, "abc")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(coll).hasSize(2);
      assertThat(vertices).hasSize(2);
    }

    session.delete(results.getFirst().asEntity());

    try (var vertices = session.query(query)) {
      try (var stream = index.getRids(session, "abc")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(coll).hasSize(1);
      assertThat(vertices).hasSize(1);
    }

    var iterator = coll.iterator();
    var i = 0;
    while (iterator.hasNext()) {
      iterator.next();
      i++;
    }
    Assert.assertEquals(i, 1);
    assertThat(index.size(session)).isEqualTo(1);
    session.rollback();

    session.begin();
    query = "select from C1 where search_fields(['p1'], 'abc' )=true ";

    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
    }
    assertThat(index.size(session)).isEqualTo(1);
    session.commit();
  }

  @Test
  public void txUpdateTest() {

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");
    var c1 = session.getMetadata().getSchema().getClassInternal("C1");
    c1.truncate();

    session.begin();
    Assert.assertEquals(index.size(session), 0);

    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "update");

    var query = "select from C1 where search_fields(['p1'], \"update\")=true ";
    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(1);
    }
    Assert.assertEquals(1, index.size(session));

    session.commit();

    session.begin();
    List<Result> results;
    try (var vertices = session.execute(query)) {
      try (var resultStream = vertices.stream()) {
        results = resultStream.collect(Collectors.toList());
      }
    }

    Collection coll;
    try (var stream = index.getRids(session, "update")) {
      coll = stream.collect(Collectors.toList());
    }

    assertThat(results).hasSize(1);
    assertThat(coll).hasSize(1);
    assertThat(index.size(session)).isEqualTo(1);

    var record = results.getFirst();
    Entity identifiable = record.asEntity();
    var activeTx = session.getActiveTransaction();
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    var element = activeTx.<Entity>load(identifiable);
    element.setProperty("p1", "removed");

    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(0);
    }
    Assert.assertEquals(1, index.size(session));

    query = "select from C1 where search_fields(['p1'], \"removed\")=true ";
    try (var vertices = session.execute(query)) {
      try (var stream = index.getRids(session, "removed")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);
    }

    Assert.assertEquals(1, coll.size());

    session.rollback();

    session.begin();
    query = "select from C1 where search_fields(['p1'], \"update\")=true ";
    try (var vertices = session.execute(query)) {
      try (var stream = index.getRids(session, "update")) {
        coll = stream.collect(Collectors.toList());
      }
      assertThat(vertices).hasSize(1);
    }
    assertThat(coll).hasSize(1);
    assertThat(index.size(session)).isEqualTo(1);
    session.commit();
  }

  @Test
  public void txUpdateTestComplex() {

    var index = session.getSharedContext().getIndexManager().getIndex("C1.p1");
    var c1 = session.getMetadata().getSchema().getClassInternal("C1");
    c1.truncate();

    session.begin();
    Assert.assertEquals(index.size(session), 0);

    var doc = ((EntityImpl) session.newVertex("c1"));
    doc.setProperty("p1", "abc");

    var doc1 = ((EntityImpl) session.newVertex("c1"));
    doc1.setProperty("p1", "abc");

    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    doc.setProperty("p1", "removed");

    var query = "select from C1 where search_fields(['p1'], \"abc\")=true ";
    Collection coll;
    try (var vertices = session.execute(query)) {
      try (var stream = index.getRids(session, "abc")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);
      Assert.assertEquals(1, coll.size());
    }

    var iterator = coll.iterator();
    var i = 0;
    RecordId rid = null;
    while (iterator.hasNext()) {
      rid = (RecordId) iterator.next();
      i++;
    }

    Assert.assertEquals(i, 1);
    Assert.assertNotNull(rid);
    Assert.assertEquals(doc1.getIdentity().toString(), rid.getIdentity().toString());
    Assert.assertEquals(index.size(session), 2);

    query = "select from C1 where search_fields(['p1'], \"removed\")=true ";
    try (var vertices = session.execute(query)) {
      try (var stream = index.getRids(session, "removed")) {
        coll = stream.collect(Collectors.toList());
      }

      assertThat(vertices).hasSize(1);
      Assert.assertEquals(coll.size(), 1);
    }

    session.rollback();

    session.begin();
    query = "select from C1 where search_fields(['p1'], \"abc\")=true ";
    try (var vertices = session.execute(query)) {
      assertThat(vertices).hasSize(2);
    }

    Assert.assertEquals(index.size(session), 2);
    session.commit();
  }
}
