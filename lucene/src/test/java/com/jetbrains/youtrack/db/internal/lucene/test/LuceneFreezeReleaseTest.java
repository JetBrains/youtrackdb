package com.jetbrains.youtrack.db.internal.lucene.test;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneFreezeReleaseTest extends BaseLuceneTest {
  @Test
  @Ignore
  public void freezeReleaseTest() {
    if (isWindows()) {
      return;
    }

    Schema schema = session.getMetadata().getSchema();
    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);

    session.command("create index Person.name on Person (name) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    ((EntityImpl) session.newEntity("Person")).field("name", "John");
    session.commit();

    var results = session.query("select from Person where name lucene 'John'");
    Assert.assertEquals(1, results.stream().count());
    session.freeze();

    results = session.query("select from Person where name lucene 'John'");
    Assert.assertEquals(1, results.stream().count());

    session.release();

    session.begin();
    ((EntityImpl) session.newEntity("Person")).field("name", "John");
    session.commit();

    results = session.query("select from Person where name lucene 'John'");
    Assert.assertEquals(2, results.stream().count());
  }

  // With double calling freeze/release
  @Test
  @Ignore
  public void freezeReleaseMisUsageTest() {
    if (isWindows()) {
      return;
    }

    Schema schema = session.getMetadata().getSchema();
    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);

    session.command("create index Person.name on Person (name) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    ((EntityImpl) session.newEntity("Person")).field("name", "John");
    session.commit();

    var results = session.query("select from Person where name lucene 'John'");
      Assert.assertEquals(1, results.stream().count());

    session.freeze();

    session.freeze();

    results = session.query("select from Person where name lucene 'John'");
      Assert.assertEquals(1, results.stream().count());

    session.release();
    session.release();

    session.begin();
    ((EntityImpl) session.newEntity("Person")).field("name", "John");
    session.commit();

    results = session.query("select from Person where name lucene 'John'");
      Assert.assertEquals(2, results.stream().count());
  }

  private static boolean isWindows() {
    final var osName = System.getProperty("os.name").toLowerCase();
    return osName.contains("win");
  }
}
