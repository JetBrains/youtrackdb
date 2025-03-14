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
    session.command("create index Person.name_tags on Person (name,tags) FULLTEXT ENGINE LUCENE")
        .close();

    var city = schema.createClass("City");
    city.createProperty("name", PropertyType.STRING);
    city.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    //noinspection deprecation
    session.command("create index City.tags on City (tags) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void testIndexingList() {

    Schema schema = session.getMetadata().getSchema();

    // Rome
    var doc = ((EntityImpl) session.newEntity("City"));
    doc.field("name", "Rome");
    doc.field(
        "tags",
        new ArrayList<String>() {
          {
            add("Beautiful");
            add("Touristic");
            add("Sunny");
          }
        });

    session.begin();
    session.commit();

    var tagsIndex = session.getClassInternal("City").getClassIndex(session, "City.tags");
    Collection<?> coll;
    try (var stream = tagsIndex.getRids(session, "Sunny")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(1);

    doc = session.load((RID) coll.iterator().next());

    assertThat(doc.<String>field("name")).isEqualTo("Rome");

    // London
    doc = ((EntityImpl) session.newEntity("City"));
    doc.field("name", "London");
    doc.field(
        "tags",
        new ArrayList<String>() {
          {
            add("Beautiful");
            add("Touristic");
            add("Sunny");
          }
        });
    session.begin();
    session.commit();

    session.begin();
    try (var stream = tagsIndex.getRids(session, "Sunny")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(2);

    doc = session.bindToSession(doc);
    // modify london: it is rainy
    List<String> tags = doc.field("tags");
    tags.remove("Sunny");
    tags.add("Rainy");

    session.commit();

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
  }

  @Test
  public void testCompositeIndexList() {

    Schema schema = session.getMetadata().getSchema();

    var doc = ((EntityImpl) session.newEntity("Person"));
    doc.field("name", "Enrico");
    doc.field(
        "tags",
        new ArrayList<String>() {
          {
            add("Funny");
            add("Tall");
            add("Geek");
          }
        });

    session.begin();
    session.commit();

    var idx = session.getClassInternal("Person").getClassIndex(session, "Person.name_tags");
    Collection<?> coll;
    try (var stream = idx.getRids(session, "Enrico")) {
      coll = stream.collect(Collectors.toList());
    }

    assertThat(coll).hasSize(3);

    doc = ((EntityImpl) session.newEntity("Person"));
    doc.field("name", "Jared");
    doc.field(
        "tags",
        new ArrayList<String>() {
          {
            add("Funny");
            add("Tall");
          }
        });

    session.begin();
    session.commit();

    session.begin();
    try (var stream = idx.getRids(session, "Jared")) {
      coll = stream.collect(Collectors.toList());
    }

    assertThat(coll).hasSize(2);

    doc = session.bindToSession(doc);
    List<String> tags = doc.field("tags");

    tags.remove("Funny");
    tags.add("Geek");

    session.commit();

    try (var stream = idx.getRids(session, "Funny")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(1);

    try (var stream = idx.getRids(session, "Geek")) {
      coll = stream.collect(Collectors.toList());
    }
    assertThat(coll).hasSize(2);

    var query = session.query("select from Person where [name,tags] lucene 'Enrico'");

    assertThat(query).hasSize(1);

    query = session.query("select from (select from Person where [name,tags] lucene 'Enrico')");

    assertThat(query).hasSize(1);

    query = session.query("select from Person where [name,tags] lucene 'Jared'");

    assertThat(query).hasSize(1);

    query = session.query("select from Person where [name,tags] lucene 'Funny'");

    assertThat(query).hasSize(1);

    query = session.query("select from Person where [name,tags] lucene 'Geek'");

    assertThat(query).hasSize(2);

    query = session.query(
        "select from Person where [name,tags] lucene '(name:Enrico AND tags:Geek)'");

    assertThat(query).hasSize(1);
  }

  @Test
  public void rname() {
    final var c1 = session.createVertexClass("C1");
    c1.createProperty("p1", PropertyType.STRING);

    var metadata = Map.<String, Object>of("default", "org.apache.lucene.analysis.en.EnglishAnalyzer");

    c1.createIndex("p1", "FULLTEXT", null, metadata, "LUCENE", new String[]{"p1"});

    session.begin();
    final var vertex = session.newVertex("C1");
    vertex.setProperty("p1", "testing");

    session.commit();

    var search = session.query("SELECT from C1 WHERE p1 LUCENE \"tested\"");

    assertThat(search).hasSize(1);
  }
}
