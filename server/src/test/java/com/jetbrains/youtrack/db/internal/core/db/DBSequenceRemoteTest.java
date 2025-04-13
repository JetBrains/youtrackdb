package com.jetbrains.youtrack.db.internal.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.server.AbstractRemoteTest;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class DBSequenceRemoteTest extends AbstractRemoteTest {

  DatabaseSessionInternal db;

  @Override
  public void setup() throws Exception {
    super.setup();
    YouTrackDB factory =
        new YouTrackDBImpl("remote:localhost", "root", "root",
            YouTrackDBConfig.defaultConfig());
    db = (DatabaseSessionInternal) factory.open(name.getMethodName(), "admin", "admin");
  }

  @Override
  public void teardown() {
    db.close();
    super.teardown();
  }

  @Test
  public void shouldSequenceWithDefaultValueNoTx() {

    db.command("CREATE CLASS Person EXTENDS V");
    db.command("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
    db.command(
        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
            + " \"sequence('personIdSequence').next()\");");
    db.command("CREATE INDEX Person.id ON Person (id) UNIQUE");

    db.begin();
    for (int i = 0; i < 10; i++) {
      Vertex person = db.newVertex("Person");
      person.setProperty("name", "Foo" + i);
      person.setProperty("id", 1000 + i);
      person.save();
    }
    db.commit();

    assertThat(db.countClass("Person")).isEqualTo(10);
  }

  // this test fails because of schema push with null session,
  // need to be fixed before mering to develop
  @Ignore
  @Test
  public void shouldSequenceWithDefaultValueTx() {

    db.command("CREATE CLASS Person EXTENDS V");
    db.command("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
    db.command(
        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
            + " \"sequence('personIdSequence').next()\");");
    db.command("CREATE INDEX Person.id ON Person (id) UNIQUE");

    db.begin();

    for (int i = 0; i < 10; i++) {
      Vertex person = db.newVertex("Person");
      person.setProperty("name", "Foo" + i);
      person.save();
    }

    db.commit();

    assertThat(db.countClass("Person")).isEqualTo(10);
  }

  @Test
  public void testCreateCachedSequenceInTx() {
    db.begin();
    db.command("CREATE SEQUENCE CircuitSequence TYPE CACHED START 1 INCREMENT 1 CACHE 10;");
    db.commit();

    db.command("select sequence('CircuitSequence').next() as seq");
  }

  @Test
  public void testCreateOrderedSequenceInTx() {
    db.begin();
    db.command("CREATE SEQUENCE CircuitSequence TYPE ORDERED;");
    db.commit();

    db.command("select sequence('CircuitSequence').next() as seq");
  }
}
