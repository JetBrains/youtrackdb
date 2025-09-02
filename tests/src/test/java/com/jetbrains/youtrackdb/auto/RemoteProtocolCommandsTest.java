package com.jetbrains.youtrackdb.auto;

import org.testng.annotations.Test;

public class RemoteProtocolCommandsTest {

  private static final String serverPort = System.getProperty("youtrackdb.server.port", "2424");

  @Test(enabled = false)
  public void testConnect() {
//    final var admin =
//        new ServerAdmin("remote:localhost:" + serverPort)
//            .connect("root", SERVER_PASSWORD);
//    admin.close();
  }

  @Test(enabled = false)
  public void testListDatabasesMemoryDB() {
//    final var admin =
//        new ServerAdmin("remote:localhost")
//            .connect("root", SERVER_PASSWORD);
//    try {
//      final var random = new Random();
//
//      final var diskDatabaseName = "diskTestListDatabasesMemoryDB" + random.nextInt();
//      admin.createDatabase(diskDatabaseName, "graph", "disk");
//
//      final var memoryDatabaseName = "memoryTestListDatabasesMemoryDB" + random.nextInt();
//      admin.createDatabase(memoryDatabaseName, "graph", "memory");
//
//      final var list = admin.listDatabases();
//
//      Assert.assertTrue(list.containsKey(diskDatabaseName), "Check disk db is in list");
//      Assert.assertTrue(list.containsKey(memoryDatabaseName), "Check memory db is in list");
//    } finally {
//      admin.close();
//    }
  }
}
