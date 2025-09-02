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

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneListIndexingTest extends BaseLuceneTest {

  public LuceneListIndexingTest() {
    super();
  }

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();

    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);
    person.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    //noinspection deprecation
    session.execute("create index Person.name_tags on Person (name,tags) FULLTEXT ENGINE LUCENE")
        .close();

    var city = schema.createClass("City");
    city.createProperty("name", PropertyType.STRING);
    city.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    //noinspection deprecation
    session.execute("create index City.tags on City (tags) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void testIndexingList() {
    session.begin();
    // Rome
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "Rome");
    doc.newEmbeddedList("tags", new ArrayList<String>() {
      {
        add("Beautiful");
        add("Touristic");
        add("Sunny");
      }
    });
    session.commit();
    session.begin();
    var tagsIndex = session.getClassInternal("City").getClassIndex(session, "City.tags");
    Collection<?> coll;
    try (var stream = tagsIndex.getRids(session, "Sunny")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(1);

    doc = session.load((RID) coll.iterator().next());

    assertThat(doc.<String>getProperty("name")).isEqualTo("Rome");

    // London
    doc = ((EntityImpl) session.newEntity("City"));
    doc.setProperty("name", "London");
    doc.newEmbeddedList("tags", new ArrayList<String>() {
      {
        add("Beautiful");
        add("Touristic");
        add("Sunny");
      }
    });

    session.commit();
    session.begin();
    try (var stream = tagsIndex.getRids(session, "Sunny")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(2);

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    // modify london: it is rainy
    List<String> tags = doc.getProperty("tags");
    tags.remove("Sunny");
    tags.add("Rainy");
    session.commit();

    session.begin();
    try (var stream = tagsIndex.getRids(session, "Rainy")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(1);

    try (var stream = tagsIndex.getRids(session, "Beautiful")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(2);

    try (var stream = tagsIndex.getRids(session, "Sunny")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(1);
    session.commit();
  }

  @Test
  public void testCompositeIndexList() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity("Person"));
    doc.setProperty("name", "Enrico");
    doc.newEmbeddedList("tags", new ArrayList<String>() {
      {
        add("Funny");
        add("Tall");
        add("Geek");
      }
    });
    session.commit();

    session.begin();
    var idx = session.getClassInternal("Person").getClassIndex(session, "Person.name_tags");
    Collection<?> coll;
    try (var stream = idx.getRids(session, "Enrico")) {
      coll = stream.collect(Collectors.toList());
    }

    assertThat(coll).hasSize(3);

    doc = ((EntityImpl) session.newEntity("Person"));
    doc.setProperty("name", "Jared");
    doc.newEmbeddedList("tags", new ArrayList<String>() {
      {
        add("Funny");
        add("Tall");
      }
    });
    session.commit();
    session.begin();
    try (var stream = idx.getRids(session, "Jared")) {
      coll = stream.collect(Collectors.toList());
    }

    assertThat(coll).hasSize(2);

    var activeTx = session.getActiveTransaction();
    doc = activeTx.load(doc);
    List<String> tags = doc.getProperty("tags");

    tags.remove("Funny");
    tags.add("Geek");
    session.commit();

    session.begin();
    try (var stream = idx.getRids(session, "Funny")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(1);

    try (var stream = idx.getRids(session, "Geek")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(2);

    var query = session.query("select from Person where [name,tags] lucene 'Enrico'");

    assertThat(IteratorUtils.count(query)).isEqualTo(1);

    query = session.query("select from (select from Person where [name,tags] lucene 'Enrico')");

    assertThat(IteratorUtils.count(query)).isEqualTo(1);

    query = session.query("select from Person where [name,tags] lucene 'Jared'");

    assertThat(IteratorUtils.count(query)).isEqualTo(1);

    query = session.query("select from Person where [name,tags] lucene 'Funny'");

    assertThat(IteratorUtils.count(query)).isEqualTo(1);

    query = session.query("select from Person where [name,tags] lucene 'Geek'");

    assertThat(IteratorUtils.count(query)).isEqualTo(2);

    query = session.query(
        "select from Person where [name,tags] lucene '(name:Enrico AND tags:Geek)'");

    assertThat(IteratorUtils.count(query)).isEqualTo(1);
    session.commit();
  }

  @Test
  public void rname() {
    final var c1 = session.createVertexClass("C1");
    c1.createProperty("p1", PropertyType.STRING);

    var metadata = Map.<String, Object>of("default",
        "org.apache.lucene.analysis.en.EnglishAnalyzer");

    c1.createIndex("p1", "FULLTEXT", null, metadata, "LUCENE", new String[]{"p1"});

    session.begin();
    final var vertex = session.newVertex("C1");
    vertex.setProperty("p1", "testing");

    session.commit();

    var search = session.query("SELECT from C1 WHERE p1 LUCENE \"tested\"");

    assertThat(IteratorUtils.count(search)).isEqualTo(1);
  }
}
