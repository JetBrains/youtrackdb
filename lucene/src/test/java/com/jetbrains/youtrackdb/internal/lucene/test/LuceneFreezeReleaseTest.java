package com.jetbrains.youtrackdb.internal.lucene.test;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
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

    Schema schema = session.getMetadata().getSlowMutableSchema();
    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);

    session.execute("create index Person.name on Person (name) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    var entity1 = ((EntityImpl) session.newEntity("Person"));
    entity1.setProperty("name", "John");
    session.commit();

    session.begin();
    var results = session.query("select from Person where name lucene 'John'");
    Assert.assertEquals(1, results.stream().count());
    session.commit();
    session.freeze();

    session.begin();
    results = session.query("select from Person where name lucene 'John'");
    Assert.assertEquals(1, results.stream().count());
    session.commit();

    session.release();

    session.begin();
    var entity = ((EntityImpl) session.newEntity("Person"));
    entity.setProperty("name", "John");
    session.commit();

    session.begin();
    results = session.query("select from Person where name lucene 'John'");
    Assert.assertEquals(2, results.stream().count());
    session.commit();
  }

  // With double calling freeze/release
  @Test
  @Ignore
  public void freezeReleaseMisUsageTest() {
    if (isWindows()) {
      return;
    }

    Schema schema = session.getMetadata().getSlowMutableSchema();
    var person = schema.createClass("Person");
    person.createProperty("name", PropertyType.STRING);

    session.execute("create index Person.name on Person (name) FULLTEXT ENGINE LUCENE").close();

    session.begin();
    EntityImpl entity1 = ((EntityImpl) session.newEntity("Person"));
    entity1.setProperty("name", "John");
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
    EntityImpl entity = ((EntityImpl) session.newEntity("Person"));
    entity.setProperty("name", "John");
    session.commit();

    results = session.query("select from Person where name lucene 'John'");
      Assert.assertEquals(2, results.stream().count());
  }

  private static boolean isWindows() {
    final var osName = System.getProperty("os.name").toLowerCase();
    return osName.contains("win");
  }
}
