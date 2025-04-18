package com.jetbrains.youtrack.db.internal.lucene.integration;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneCreateIndexIntegrationTest {

  private YouTrackDBServer server0;
  private YouTrackDB remote;

  @Before
  public void before() throws Exception {
    server0 =
        YouTrackDBServer.startFromClasspathConfig(
            "com/orientechnologies/lucene/integration/youtrackdb-simple-server-config.xml");
    remote = new YouTrackDBAbstract("remote:localhost", "root", "test",
        YouTrackDBConfig.defaultConfig());

    remote.execute(
        "create database LuceneCreateIndexIntegrationTest disk users(admin identified by 'admin'"
            + " role admin) ");
    final var session =
        remote.open("LuceneCreateIndexIntegrationTest", "admin", "admin");

    session.runScript("sql", "create class Person");
    session.runScript("sql", "create property Person.name STRING");
    session.runScript("sql", "create property Person.surname STRING");

    session.executeInTx(transaction -> {
      final var doc = transaction.newEntity("Person");
      doc.setProperty("name", "Jon");
      doc.setProperty("surname", "Snow");
    });

    session.close();
  }

  @Test
  public void testCreateIndexJavaAPI() {
    final var session =
        (DatabaseSessionInternal) remote.open("LuceneCreateIndexIntegrationTest", "admin",
            "admin");
    var person = session.getMetadata().getSchema().getClass("Person");

    if (person == null) {
      person = session.getMetadata().getSchema().createClass("Person");
    }
    if (!person.existsProperty("name")) {
      person.createProperty("name", PropertyType.STRING);
    }
    if (!person.existsProperty("surname")) {
      person.createProperty("surname", PropertyType.STRING);
    }

    person.createIndex(
        "Person.firstName_lastName",
        "FULLTEXT",
        null,
        null,
        "LUCENE", new String[]{"name", "surname"});
    Assert.assertTrue(
        session.getMetadata().getSchema().getClassInternal("Person")
            .areIndexed(session, "name", "surname"));
  }

  @After
  public void after() {
    remote.drop("LuceneCreateIndexIntegrationTest");
    server0.shutdown();
  }
}
