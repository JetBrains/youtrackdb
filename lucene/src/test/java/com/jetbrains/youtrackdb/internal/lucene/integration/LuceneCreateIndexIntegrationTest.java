package com.jetbrains.youtrackdb.internal.lucene.integration;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.api.remote.RemoteYouTrackDB;
import com.jetbrains.youtrackdb.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneCreateIndexIntegrationTest {

  private YouTrackDBServer server0;
  private RemoteYouTrackDB remote;

  @Before
  public void before() throws Exception {
    server0 =
        YouTrackDBServer.startFromClasspathConfig(
            "com/jetbrains.youtrackdb/lucene/integration/youtrackdb-simple-server-config.xml");
    remote = YourTracks.remote("remote:localhost", "root", "test",
        YouTrackDBConfig.defaultConfig());

    remote.execute(
        "create database LuceneCreateIndexIntegrationTest disk users(admin identified by 'admin'"
            + " role admin) ");
    final var session =
        remote.open("LuceneCreateIndexIntegrationTest", "admin", "admin");

    session.computeScript("sql", "create class Person");
    session.computeScript("sql", "create property Person.name STRING");
    session.computeScript("sql", "create property Person.surname STRING");

    session.executeSQLScript("""
        begin;
        insert into Person set name = 'Jon', surname = 'Snow';
        commit;
        """);

    session.close();
  }

  @Test
  public void testCreateIndexJavaAPI() {
    final var session =
        remote.open("LuceneCreateIndexIntegrationTest", "admin",
            "admin");
    session.execute("create class Person if not exists");
    session.execute("create property Person.name if not exists STRING");
    session.execute("create property Person.surname if not exists STRING");
    session.execute(
        "create index Person.firstName_lastName on Person (name, surname) FULLTEXT ENGINE LUCENE");

    try (var rs = session.query(
        "select from metadata:indexes where name = 'Person.firstName_lastName'")) {
      Assert.assertTrue(rs.hasNext());
      var result = rs.next();
      Assert.assertEquals("Person.firstName_lastName", result.getString("name"));
      Assert.assertEquals("Person", result.getString("className"));
      Assert.assertEquals(List.of("name", "surname"), result.<String>getEmbeddedList("properties"));
      Assert.assertEquals(INDEX_TYPE.FULLTEXT.name(), result.getString("type"));
    }
  }

  @After
  public void after() {
    remote.drop("LuceneCreateIndexIntegrationTest");
    server0.shutdown();
  }
}
