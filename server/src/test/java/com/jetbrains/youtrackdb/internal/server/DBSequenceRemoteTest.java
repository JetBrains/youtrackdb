package com.jetbrains.youtrackdb.internal.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.remote.RemoteDatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import org.junit.Ignore;
import org.junit.Test;

public class DBSequenceRemoteTest extends AbstractRemoteTest {

  RemoteDatabaseSession session;

  @Override
  public void setup() throws Exception {
    super.setup();
    var factory =
        (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:localhost", "root", "root");
    session = factory.open(name.getMethodName(), "admin", "admin");
  }

  @Override
  public void teardown() {
    session.close();
    super.teardown();
  }

  @Ignore
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
          create vertex Person set name = "Foo" + $i, id = 1000 + $i;
          let $i = $i + 1;
        }
        commit;
        """);

    assertThat(
        session.query("select count(*) as count from Person").
            findFirst(res -> res.getLong("count"))
            .intValue()).isEqualTo(10);
  }

  @Ignore
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
          create vertex Person set name = "Foo" + $i;
          let $i = $i + 1;
        }
        commit;
        """);

    assertThat(
        session.query("select count(*) as count from Person").findFirst(res -> res.getLong("count"))
            .intValue()).isEqualTo(10);
  }

  @Ignore
  @Test
  public void testCreateCachedSequenceInTx() {
    session.executeSQLScript("""
        begin;
        CREATE SEQUENCE CircuitSequence TYPE CACHED START 1 INCREMENT 1 CACHE 10;
        commit;
        """);

    session.query("select sequence('CircuitSequence').next() as seq");
  }

  @Ignore
  @Test
  public void testCreateOrderedSequenceInTx() {
    session.executeSQLScript("""
        begin;
        CREATE SEQUENCE CircuitSequence TYPE ORDERED;
        commit;
        """);

    session.query("select sequence('CircuitSequence').next() as seq");
  }
}
