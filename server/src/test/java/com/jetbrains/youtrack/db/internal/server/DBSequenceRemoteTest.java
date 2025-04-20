package com.jetbrains.youtrack.db.internal.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.remote.RemoteDatabaseSession;
import org.junit.Test;

/**
 *
 */
public class DBSequenceRemoteTest extends AbstractRemoteTest {

  RemoteDatabaseSession session;

  @Override
  public void setup() throws Exception {
    super.setup();
    var factory =
        YourTracks.remote("remote:localhost", "root", "root",
            YouTrackDBConfig.defaultConfig());
    session = factory.open(name.getMethodName(), "admin", "admin");
  }

  @Override
  public void teardown() {
    session.close();
    super.teardown();
  }

  @Test
  public void shouldSequenceWithDefaultValueNoTx() {
    session.execute("CREATE CLASS Person EXTENDS V");
    session.execute("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
    session.execute(
        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
            + " \"sequence('personIdSequence').next()\");");
    session.execute("CREATE INDEX Person.id ON Person (id) UNIQUE");

    session.executeSQLScript("""
        let $i = 0;
        begin;
        while ($i < 10) {
          let $person = create vertex Person set name = "Foo" + $i, id = 1000 + $i;
          $i = $i + 1;
        }
        commit;
        """);

    assertThat(
        session.query("select count(*) as count from Person").
            findFirst(res -> res.getInt("count"))
            .intValue()).isEqualTo(10);
  }

  @Test
  public void shouldSequenceWithDefaultValueTx() {

    session.execute("CREATE CLASS Person EXTENDS V");
    session.execute("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
    session.execute(
        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
            + " \"sequence('personIdSequence').next()\");");
    session.execute("CREATE INDEX Person.id ON Person (id) UNIQUE");

    session.executeSQLScript("""
        let $i = 0;
        begin;
        while ($i < 10) {
          let $person = create vertex Person set name = "Foo" + $i
          $i = $i + 1;
        }
        commit;
        """);

    assertThat(session.query("select count(*) from Person").findFirst(res -> res.getInt("count"))
        .intValue()).isEqualTo(10);
  }

  @Test
  public void testCreateCachedSequenceInTx() {
    session.executeSQLScript("""
        begin;
        CREATE SEQUENCE CircuitSequence TYPE CACHED START 1 INCREMENT 1 CACHE 10;
        commit;
        """);

    session.command("select sequence('CircuitSequence').next() as seq");
  }

  @Test
  public void testCreateOrderedSequenceInTx() {
    session.executeSQLScript("""
        begin;
        CREATE SEQUENCE CircuitSequence TYPE ORDERED;
        commit;
        """);

    session.command("select sequence('CircuitSequence').next() as seq");
  }
}
