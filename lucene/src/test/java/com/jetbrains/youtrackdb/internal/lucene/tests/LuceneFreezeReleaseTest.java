package com.jetbrains.youtrackdb.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneFreezeReleaseTest extends LuceneBaseTest {

  @Before
  public void setUp() throws Exception {

    dropDatabase();
    createDatabase(DatabaseType.DISK);
  }

  @Test
  public void freezeReleaseTest() {
    Schema schema = session.getMetadata().getSchema();
    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);

    session.execute("create index Person.name on Person (name) FULLTEXT ENGINE LUCENE");

    session.begin();
    var entity = ((EntityImpl) session.newEntity("Person"));
    entity.setProperty("name", "John");
    session.commit();

    session.begin();
    var results = session.query("select from Person where search_class('John')=true");

    assertThat(IteratorUtils.count(results)).isEqualTo(1);
    results.close();
    session.commit();
    session.freeze();

    session.begin();
    results = session.execute("select from Person where search_class('John')=true");
    assertThat(IteratorUtils.count(results)).isEqualTo(1);
    results.close();
    session.commit();

    session.release();

    session.begin();
    var doc = session.newInstance("Person");
    doc.setProperty("name", "John");

    results = session.query("select from Person where search_class('John')=true");
    assertThat(IteratorUtils.count(results)).isEqualTo(2);
    results.close();

    session.commit();
  }

  // With double calling freeze/release
  @Test
  public void freezeReleaseMisUsageTest() {

    Schema schema = session.getMetadata().getSchema();
    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);

    session.execute("create index Person.name on Person (name) FULLTEXT ENGINE LUCENE");

    session.begin();
    var entity1 = ((EntityImpl) session.newEntity("Person"));
    entity1.setProperty("name", "John");
    session.commit();

    session.begin();
    var results = session.execute("select from Person where search_class('John')=true");

    assertThat(IteratorUtils.count(results)).isEqualTo(1);
    results.close();
    session.commit();

    session.freeze();

    session.freeze();

    session.begin();
    results = session.execute("select from Person where search_class('John')=true");

    assertThat(IteratorUtils.count(results)).isEqualTo(1);
    results.close();
    session.commit();

    session.release();
    session.release();

    session.begin();
    EntityImpl entity = ((EntityImpl) session.newEntity("Person"));
    entity.setProperty("name", "John");
    session.commit();

    session.begin();
    results = session.execute("select from Person where search_class('John')=true");
    assertThat(IteratorUtils.count(results)).isEqualTo(2);
    results.close();
    session.commit();
  }
}
