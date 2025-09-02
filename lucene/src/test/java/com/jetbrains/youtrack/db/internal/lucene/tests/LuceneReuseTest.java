package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Date;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Test;

/**
 *
 */
public class LuceneReuseTest extends LuceneBaseTest {

  @Test
  public void shouldUseTheRightIndex() {

    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("Reuse");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("date", PropertyType.DATETIME);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createProperty("age", PropertyType.LONG);

    session.execute("create index Reuse.composite on Reuse (name,surname,date,age) UNIQUE");
    session.execute("create index Reuse.surname on Reuse (surname) FULLTEXT ENGINE LUCENE");

    for (var i = 0; i < 10; i++) {
      session.begin();
      ((EntityImpl) session.newEntity("Reuse"))
          .setPropertyInChain("name", "John")
          .setPropertyInChain("date", new Date())
          .setPropertyInChain("surname", "Reese")
          .setProperty("age", i);
      session.commit();
    }

    session.begin();
    var results =
        session.execute("SELECT FROM Reuse WHERE name='John' and search_class('Reese') =true");

    assertThat(IteratorUtils.count(results)).isEqualTo(10);

    results = session.execute(
        "SELECT FROM Reuse WHERE search_class('Reese')=true  and name='John'");

    assertThat(IteratorUtils.count(results)).isEqualTo(10);
    session.commit();
  }

  @Test
  public void shouldUseTheRightLuceneIndex() {

    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("Reuse");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("date", PropertyType.DATETIME);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createProperty("age", PropertyType.LONG);

    session.execute("create index Reuse.composite on Reuse (name,surname,date,age) UNIQUE");

    // lucene on name and surname
    session.execute(
        "create index Reuse.name_surname on Reuse (name,surname) FULLTEXT ENGINE LUCENE");

    for (var i = 0; i < 10; i++) {
      session.begin();
      ((EntityImpl) session.newEntity("Reuse"))
          .setPropertyInChain("name", "John")
          .setPropertyInChain("date", new Date())
          .setPropertyInChain("surname", "Reese")
          .setProperty("age", i);
      session.begin();
    }

    // additional record
    session.begin();
    ((EntityImpl) session.newEntity("Reuse"))
        .setPropertyInChain("name", "John")
        .setPropertyInChain("date", new Date())
        .setPropertyInChain("surname", "Franklin")
        .setProperty("age", 11);
    session.commit();

    // exact query on name uses Reuse.conposite
    var results =
        session.execute("SELECT FROM Reuse WHERE name='John' and search_class('Reese')=true");

    assertThat(IteratorUtils.count(results)).isEqualTo(10);

    results = session.execute("SELECT FROM Reuse WHERE search_class('Reese')=true and name='John'");

    assertThat(IteratorUtils.count(results)).isEqualTo(10);

    results =
        session.execute(
            "SELECT FROM Reuse WHERE name='John' AND search_class('surname:Franklin') =true");

    assertThat(IteratorUtils.count(results)).isEqualTo(1);
  }
}
