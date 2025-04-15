package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class LuceneReuseTest extends BaseLuceneTest {

  @Test
  public void shouldUseTheRightIndex() {

    Schema schema = session.getMetadata().getSchema();

    var cls = schema.createClass("Reuse");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("date", PropertyType.DATETIME);
    cls.createProperty("surname", PropertyType.STRING);
    cls.createProperty("age", PropertyType.LONG);

    session.execute("create index Reuse.composite on Reuse (name,surname,date,age) UNIQUE").close();
    session.execute("create index Reuse.surname on Reuse (surname) FULLTEXT ENGINE LUCENE").close();

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
        session.execute("SELECT FROM Reuse WHERE name='John' and surname LUCENE 'Reese'");

    Assert.assertEquals(10, results.stream().count());

    results = session.execute("SELECT FROM Reuse WHERE surname LUCENE 'Reese' and name='John'");

    Assert.assertEquals(10, results.stream().count());
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

    session.execute("create index Reuse.composite on Reuse (name,surname,date,age) UNIQUE").close();

    // lucene on name and surname
    session.execute(
            "create index Reuse.name_surname on Reuse (name,surname) FULLTEXT ENGINE LUCENE")
        .close();

    for (var i = 0; i < 10; i++) {
      session.begin();
      ((EntityImpl) session.newEntity("Reuse"))
          .setPropertyInChain("name", "John")
          .setPropertyInChain("date", new Date())
          .setPropertyInChain("surname", "Reese")
          .setProperty("age", i);
      session.commit();
    }

    // additional record
    session.begin();
    ((EntityImpl) session.newEntity("Reuse"))
        .setPropertyInChain("name", "John")
        .setPropertyInChain("date", new Date())
        .setPropertyInChain("surname", "Franklin")
        .setProperty("age", 11);
    session.commit();
    session.begin();
    var results =
        session.execute("SELECT FROM Reuse WHERE name='John' and [name,surname] LUCENE 'Reese'");

    Assert.assertEquals(10, results.stream().count());

    results = session.execute(
        "SELECT FROM Reuse WHERE [name,surname] LUCENE 'Reese' and name='John'");

    Assert.assertEquals(10, results.stream().count());

    results =
        session.execute(
            "SELECT FROM Reuse WHERE name='John' and [name,surname] LUCENE '(surname:Franklin)'");

    Assert.assertEquals(1, results.stream().count());
    session.commit();
  }
}
