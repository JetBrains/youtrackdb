package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class CountRealationshipsTest {

  private static final String SERVER_DIRECTORY = "./target/cluster";
  private YouTrackDBServer server;
  private YouTrackDB youTrackDB;

  @Before
  public void before() throws Exception {
    server = new YouTrackDBServer(false);
    server.setServerRootDirectory(SERVER_DIRECTORY);
    server.startup(getClass().getResourceAsStream("youtrackdb-server-config-tree-ridbag.xml"));
    server.activate();

    youTrackDB = new YouTrackDBImpl("remote:localhost", "root", "root",
        YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database ? memory users (admin identified by 'admin' role admin)",
        CountRealationshipsTest.class.getSimpleName());
  }

  @Test
  public void test() throws Exception {
    var session =
        youTrackDB.open(CountRealationshipsTest.class.getSimpleName(), "admin", "admin");
    session.begin();
    var vertex1 = session.newVertex("V");
    var vertex2 = session.newVertex("V");
    session.commit();

    session.begin();
    vertex1 = session.load(vertex1.getIdentity());
    vertex2 = session.load(vertex2.getIdentity());

    int version = vertex1.getProperty("@version");
    assertEquals(0, countEdges(vertex1, Direction.OUT));
    assertEquals(0, countEdges(vertex1, Direction.OUT));
    System.out.println("Version: " + version);
    System.out.println("vertex1 out: " + countEdges(vertex1, Direction.OUT));
    System.out.println("vertex2 in: " + countEdges(vertex2, Direction.IN));
    /*
     * output: Version: 1 vertex1 out: 0 vertex2 in: 0
     */

    vertex2 = session.load(vertex2.getIdentity());
    vertex1 = session.load(vertex1.getIdentity());

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

    session.commit();

    session.begin();
    vertex1 = session.load(vertex1.getIdentity());
    vertex2 = session.load(vertex2.getIdentity());

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

    session.commit();
    session.close();

    session = youTrackDB.open(CountRealationshipsTest.class.getSimpleName(), "admin", "admin");
    session.begin();
    vertex1 = session.load(vertex1.getIdentity());
    vertex2 = session.load(vertex2.getIdentity());

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
    session.commit();
  }

  private int countEdges(Vertex v, Direction dir) throws Exception {
    var c = 0;
    for (var oEdge : v.getEdges(dir)) {
      c++;
    }
    return c;
  }

  @After
  public void after() {
    youTrackDB.close();
    server.shutdown();
    YouTrackDBEnginesManager.instance().shutdown();
    FileUtils.deleteRecursively(new File(SERVER_DIRECTORY));
    YouTrackDBEnginesManager.instance().startup();
  }
}
