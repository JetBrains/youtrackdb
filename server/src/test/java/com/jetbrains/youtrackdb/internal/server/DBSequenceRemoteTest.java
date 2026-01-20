package com.jetbrains.youtrackdb.internal.server;

import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import org.junit.Ignore;
import org.junit.Test;

public class DBSequenceRemoteTest extends AbstractRemoteTest {
  YTDBGraphTraversalSource traversal;
  YouTrackDB youTrackDB;

  @Override
  public void setup() throws Exception {
    super.setup();

    youTrackDB = YourTracks.instance("localhost", "root", "root");
    traversal = youTrackDB.openTraversal(name.getMethodName(), "admin", "admin");
  }

  @Override
  public void teardown() throws Exception {
    traversal.close();
    youTrackDB.close();
    super.teardown();
  }

  @Ignore
  @Test
  public void shouldSequenceWithDefaultValueNoTx() {
//    traversal.execute("CREATE CLASS Person EXTENDS V");
//    traversal.execute("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
//    traversal.execute(
//        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
//            + " \"sequence('personIdSequence').next()\");");
//    traversal.execute("CREATE INDEX Person.id ON Person (id) UNIQUE");
//
//    traversal.executeSQLScript("""
//        let $i = 0;
//        begin;
//        while ($i < 10) {
//          create vertex Person set name = "Foo" + $i, id = 1000 + $i;
//          let $i = $i + 1;
//        }
//        commit;
//        """);
//
//    assertThat(
//        traversal.query("select count(*) as count from Person").
//            findFirst(res -> res.getLong("count"))
//            .intValue()).isEqualTo(10);
  }

  @Ignore
  @Test
  public void shouldSequenceWithDefaultValueTx() {

//    traversal.execute("CREATE CLASS Person EXTENDS V");
//    traversal.execute("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
//    traversal.execute(
//        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default"
//            + " \"sequence('personIdSequence').next()\");");
//    traversal.execute("CREATE INDEX Person.id ON Person (id) UNIQUE");
//
//    traversal.executeSQLScript("""
//        let $i = 0;
//        begin;
//        while ($i < 10) {
//          create vertex Person set name = "Foo" + $i;
//          let $i = $i + 1;
//        }
//        commit;
//        """);
//
//    assertThat(
//        traversal.query("select count(*) as count from Person").findFirst(res -> res.getLong("count"))
//            .intValue()).isEqualTo(10);
  }

  @Ignore
  @Test
  public void testCreateCachedSequenceInTx() {
//    traversal.executeSQLScript("""
//        begin;
//        CREATE SEQUENCE CircuitSequence TYPE CACHED START 1 INCREMENT 1 CACHE 10;
//        commit;
//        """);
//
//    traversal.query("select sequence('CircuitSequence').next() as seq");
  }

  @Ignore
  @Test
  public void testCreateOrderedSequenceInTx() {
//    traversal.executeSQLScript("""
//        begin;
//        CREATE SEQUENCE CircuitSequence TYPE ORDERED;
//        commit;
//        """);
//
//    traversal.query("select sequence('CircuitSequence').next() as seq");
  }
}
