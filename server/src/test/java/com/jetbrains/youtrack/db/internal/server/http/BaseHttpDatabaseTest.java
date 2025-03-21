package com.jetbrains.youtrack.db.internal.server.http;

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJackson;
import java.nio.file.Paths;
import java.util.HashMap;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

/**
 * Test HTTP "query" command.
 */
public abstract class BaseHttpDatabaseTest extends BaseHttpTest {

  @Before
  public void createDatabase() throws Exception {
    serverDirectory =
        Paths.get(System.getProperty("buildDirectory", "target"))
            .resolve(this.getClass().getSimpleName() + "Server")
            .toFile()
            .getCanonicalPath();

    super.startServer();
    var pass = new HashMap<String, Object>();
    pass.put("adminPassword", "admin");
    Assert.assertEquals(
        200,
        post("database/" + getDatabaseName() + "/memory")
            .payload(RecordSerializerJackson.mapToJson(pass), CONTENT.JSON)
            .setUserName("root")
            .setUserPassword("root")
            .getResponse()
            .getCode());

    onAfterDatabaseCreated();
  }

  @After
  public void dropDatabase() throws Exception {
    Assert.assertEquals(
        204,
        delete("database/" + getDatabaseName())
            .setUserName("root")
            .setUserPassword("root")
            .getResponse()
            .getCode());
    super.stopServer();

    onAfterDatabaseDropped();
  }

  protected void onAfterDatabaseCreated() throws Exception {
  }

  protected void onAfterDatabaseDropped() throws Exception {
  }
}
