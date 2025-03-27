package com.jetbrains.youtrack.db.auto;

import static org.testng.AssertJUnit.assertTrue;

import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.Arrays;
import java.util.Random;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 *
 */
@Test
public class RemoteProtocolCommandsTest extends BaseDBTest {

  private static final String serverPort = System.getProperty("youtrackdb.server.port", "2424");

  @Parameters(value = "remote")
  public RemoteProtocolCommandsTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test(enabled = false)
  public void testConnect() throws Exception {
    final var admin =
        new ServerAdmin("remote:localhost:" + serverPort)
            .connect("root", SERVER_PASSWORD);
    admin.close();
  }

  @Test
  public void testListDatabasesMemoryDB() throws Exception {
    final var admin =
        new ServerAdmin("remote:localhost")
            .connect("root", SERVER_PASSWORD);
    try {
      final var random = new Random();

      final var diskDatabaseName = "diskTestListDatabasesMemoryDB" + random.nextInt();
      admin.createDatabase(diskDatabaseName, "graph", "disk");

      final var memoryDatabaseName = "memoryTestListDatabasesMemoryDB" + random.nextInt();
      admin.createDatabase(memoryDatabaseName, "graph", "memory");

      final var list = admin.listDatabases();

      Assert.assertTrue(list.containsKey(diskDatabaseName), "Check disk db is in list");
      Assert.assertTrue(list.containsKey(memoryDatabaseName), "Check memory db is in list");
    } finally {
      admin.close();
    }
  }

  @Test(enabled = false)
  // This is not supported anymore direct record operations are removed from the storage, only tx is
  // available
  public void testRawCreateWithoutIDTest() {
    var clazz = this.session.getMetadata().getSchema().createClass("RidCreationTestClass");
    var storage = (AbstractPaginatedStorage) this.session.getStorage();
    var doc = ((EntityImpl) session.newEntity("RidCreationTestClass"));
    doc.setProperty("test", "test");
    var bad = new RecordId(-1, -1);
    var res =
        storage.createRecord(bad, doc.toStream(), doc.getVersion(), EntityImpl.RECORD_TYPE, null);

    // assertTrue(" the cluster is not valid", bad.clusterId >= 0);
    var ids = "";
    for (var aId : clazz.getClusterIds()) {
      ids += aId;
    }

    assertTrue(
        " returned id:" + bad.getClusterId() + " shoud be one of:" + ids,
        Arrays.binarySearch(clazz.getClusterIds(), bad.getClusterId()) >= 0);
  }
}
