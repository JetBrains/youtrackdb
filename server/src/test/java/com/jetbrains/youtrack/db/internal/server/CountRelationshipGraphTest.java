package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.Test;

public class CountRelationshipGraphTest extends AbstractRemoteTest {

  private YouTrackDBImpl youTrackDB;
  private int old;

  public void setup() throws Exception {
    old = GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.getValueAsInteger();
    GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(-1);
    super.setup();
    youTrackDB =
        new YouTrackDBImpl(
            "remote:localhost",
            "root",
            "root",
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(
                    GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD, -1)
                .build());
  }

  public void teardown() {
    GlobalConfiguration.INDEX_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(old);
    super.teardown();
  }

  @Test
  public void test() throws Exception {
    var g = youTrackDB.open(name.getMethodName(), "admin", "admin");
    g.begin();
    var vertex1 = g.newVertex("V");
    var vertex2 = g.newVertex("V");
    g.commit();

    vertex1 = g.load(vertex1.getIdentity());
    vertex2 = g.load(vertex2.getIdentity());

    int version = vertex1.getProperty("@version");
    assertEquals(0, countEdges(vertex1, Direction.OUT));
    assertEquals(0, countEdges(vertex1, Direction.OUT));
    System.out.println("Version: " + version);
    System.out.println("vertex1 out: " + countEdges(vertex1, Direction.OUT));
    System.out.println("vertex2 in: " + countEdges(vertex2, Direction.IN));
    /*
     * output: Version: 1 vertex1 out: 0 vertex2 in: 0
     */

    g.begin();

    vertex1 = g.load(vertex1.getIdentity());
    vertex2 = g.load(vertex2.getIdentity());

    vertex1.addStateFulEdge(vertex2);

    version = vertex1.getProperty("@version");
    assertEquals(1, countEdges(vertex1, Direction.OUT));
    assertEquals(1, countEdges(vertex1, Direction.OUT));
    System.out.println("Pre-commit:");
    System.out.println("Version: " + version);
    System.out.println("vertex1 out: " + countEdges(vertex1, Direction.OUT));
    System.out.println("vertex2 in: " + countEdges(vertex2, Direction.IN));
    /*
     * output: Pre-commit: Version: 1 vertex1 out: 1 vertex2 in: 1
     */

    g.commit();

    vertex1 = g.load(vertex1.getIdentity());
    vertex2 = g.load(vertex2.getIdentity());

    version = vertex1.getProperty("@version");
    assertEquals(1, countEdges(vertex1, Direction.OUT));
    assertEquals(1, countEdges(vertex1, Direction.OUT));
    System.out.println("Post-commit:");
    System.out.println("Version: " + version);
    System.out.println("vertex1 out: " + countEdges(vertex1, Direction.OUT));
    System.out.println("vertex2 in: " + countEdges(vertex2, Direction.IN));
    /*
     * output: Post-commit: Version: 2 vertex1 out: 0 <- INCORRECT vertex2 in: 0 <- INCORRECT
     */

    g.close();

    g = youTrackDB.open(name.getMethodName(), "admin", "admin");
    vertex1 = g.load(vertex1.getIdentity());
    vertex2 = g.load(vertex2.getIdentity());

    version = vertex1.getProperty("@version");
    assertEquals(1, countEdges(vertex1, Direction.OUT));
    assertEquals(1, countEdges(vertex1, Direction.OUT));
    System.out.println("Reload in new transaction:");
    System.out.println("Version: " + version);
    System.out.println("vertex1 out: " + countEdges(vertex1, Direction.OUT));
    System.out.println("vertex2 in: " + countEdges(vertex2, Direction.IN));
    /*
     * output: Reload in new transaction: Version: 2 vertex1 out: 1 vertex2 in: 1
     */
  }

  private static int countEdges(Vertex v, Direction dir) throws Exception {
    var c = 0;
    for (var edge : v.getEdges(dir)) {
      c++;
    }
    return c;
  }
}
