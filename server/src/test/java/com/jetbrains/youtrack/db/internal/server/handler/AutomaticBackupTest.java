package com.jetbrains.youtrack.db.internal.server.handler;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import org.junit.Assert;

/**
 *
 */
public class AutomaticBackupTest {

  private static final String DBNAME = "testautobackup";
  private static final String DBNAME2 = DBNAME + "2";
  private static final String DBNAME3 = DBNAME + "3";
  private static String BACKUPDIR;

  private static String URL;
  private static String URL2;
  private final String tempDirectory;
  private BasicDatabaseSession db;
  private final YouTrackDBServer server;

  public AutomaticBackupTest() throws IllegalArgumentException, SecurityException {

    // SET THE YOUTRACKDB_HOME DIRECTORY TO CHECK JSON FILE CREATION
    tempDirectory = new File("target/testhome").getAbsolutePath();
    System.setProperty("YOUTRACKDB_HOME", tempDirectory);

    server =
        new YouTrackDBServer(false) {
          @Override
          public Map<String, String> getAvailableStorageNames() {
            var result = new HashMap<String, String>();
            result.put(DBNAME, URL);
            return result;
          }
        };
  }

  // @BeforeClass
  public static void beforeClass() throws Exception {
    final var buildDirectory =
        new File(System.getProperty("buildDirectory", ".")).getAbsolutePath();

    BACKUPDIR = new File(buildDirectory, "backup").getAbsolutePath();
    URL = "disk:" + buildDirectory + File.separator + DBNAME;
    URL2 = "disk:" + buildDirectory + File.separator + DBNAME2;

    FileUtils.deleteRecursively(new File(BACKUPDIR));

    Files.createDirectories(Paths.get(BACKUPDIR));
  }

  // @AfterClass
  public static void afterClass() {
    FileUtils.deleteRecursively(new File(BACKUPDIR));
  }

  // @Before
  public void init()
      throws InstantiationException,
      IllegalAccessException,
      ClassNotFoundException,
      IllegalArgumentException,
      SecurityException,
      InvocationTargetException,
      NoSuchMethodException {
    final var f =
        new File(
            SystemVariableResolver.resolveSystemVariables(
                "${YOUTRACKDB_HOME}/config/automatic-backup.json"));
    if (f.exists()) {
      f.delete();
    }
    server.startup();
    if (server.existsDatabase(DBNAME)) {
      server.dropDatabase(DBNAME);
    }
    server
        .getContext()
        .execute("create database ? disk users (admin identified by 'admin' role admin)", DBNAME);
    db = server.getDatabases().openNoAuthorization(DBNAME);

    db.getSchema().createClass("TestBackup");
    var tx = db.begin();
    tx.newEntity("TestBackup").setProperty("name", DBNAME);

    tx.commit();
  }

  // @After
  public void deinit() {
    Assert.assertTrue(new File(tempDirectory + "/config/automatic-backup.json").exists());

    new File(tempDirectory + "/config/automatic-backup.json").delete();

    server.dropDatabase(db.getDatabaseName());
    server.shutdown();
  }

  // @Test
  public void testFullBackupWithJsonConfigInclude() throws Exception {
    if (new File(BACKUPDIR + "/testautobackup.zip").exists()) {
      new File(BACKUPDIR + "/testautobackup.zip").delete();
    }

    Assert.assertFalse(new File(tempDirectory + "/config/automatic-backup.json").exists());

    var jsonConfig =
        IOUtils.readStreamAsString(getClass().getResourceAsStream("automatic-backup.json"));
    var tx = db.begin();
    var doc = ((EntityImpl) tx.newEntity());
    doc.updateFromJSON(jsonConfig);

    doc.setProperty("enabled", true);
    doc.setProperty("targetFileName", "${DBNAME}.zip");

    doc.setProperty("dbInclude", new String[]{"testautobackup"});

    doc.setPropertyInChain(
        "firstTime",
        new SimpleDateFormat("HH:mm:ss").format(new Date(System.currentTimeMillis() + 5000)));

    IOUtils.writeFile(new File(tempDirectory + "/config/automatic-backup.json"), doc.toJSON());

    final var aBackup = new AutomaticBackup();

    final var config = new ServerParameterConfiguration[]{};

    aBackup.config(server, config);

    waitForFile(Paths.get(BACKUPDIR).resolve("testautobackup.zip"));
    aBackup.sendShutdown();

    if (server.existsDatabase(DBNAME2)) {
      server.dropDatabase(DBNAME2);
    }
    server
        .getContext()
        .execute(
            "create database ? disk users (admin identified by 'admin' role admin)", DBNAME2);
    var database2 = server.getDatabases().openNoAuthorization(DBNAME2);

    // database2.restore(new FileInputStream(BACKUPDIR + "/testautobackup.zip"), null, null, null);

    Assert.assertEquals(database2.countClass("TestBackup"), 1);
    database2.close();
  }

  // @Test
  public void testFullBackupWithJsonConfigExclude() throws Exception {
    if (new File(BACKUPDIR + "/testautobackup.zip").exists()) {
      new File(BACKUPDIR + "/testautobackup.zip").delete();
    }

    Assert.assertFalse(new File(tempDirectory + "/config/automatic-backup.json").exists());

    var jsonConfig =
        IOUtils.readStreamAsString(getClass().getResourceAsStream("automatic-backup.json"));

    var tx = db.begin();
    var doc = ((EntityImpl) tx.newEntity());
    doc.updateFromJSON(jsonConfig);

    doc.setProperty("enabled", true);
    doc.setProperty("targetFileName", "${DBNAME}.zip");

    doc.setProperty("dbExclude", new String[]{"testautobackup"});

    doc.setPropertyInChain(
        "firstTime",
        new SimpleDateFormat("HH:mm:ss").format(new Date(System.currentTimeMillis() + 2000)));

    IOUtils.writeFile(new File(tempDirectory + "/config/automatic-backup.json"), doc.toJSON());

    final var aBackup = new AutomaticBackup();

    final var config = new ServerParameterConfiguration[]{};

    aBackup.config(server, config);

    try {
      Thread.sleep(5000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    aBackup.sendShutdown();

    Assert.assertFalse(new File(BACKUPDIR + "/testautobackup.zip").exists());
  }

  // @Test
  public void testFullBackup() throws Exception {
    if (new File(BACKUPDIR + "/fullBackup.zip").exists()) {
      new File(BACKUPDIR + "/fullBackup.zip").delete();
    }

    final var aBackup = new AutomaticBackup();

    final var config =
        new ServerParameterConfiguration[]{
            new ServerParameterConfiguration("enabled", "true"),
            new ServerParameterConfiguration(
                "firstTime",
                new SimpleDateFormat("HH:mm:ss").format(
                    new Date(System.currentTimeMillis() + 5000))),
            new ServerParameterConfiguration("delay", "1d"),
            new ServerParameterConfiguration("mode", "FULL_BACKUP"),
            new ServerParameterConfiguration("target.directory", BACKUPDIR),
            new ServerParameterConfiguration("target.fileName", "fullBackup.zip")
        };

    aBackup.config(server, config);

    waitForFile(Paths.get(BACKUPDIR).resolve("fullBackup.zip"));

    aBackup.sendShutdown();

    final DatabaseSessionInternal database2 = new DatabaseDocumentTx(URL2);
    if (database2.exists()) {
      ((DatabaseSessionInternal) database2.open("admin", "admin")).drop();
    }
    database2.create();

    // database2.restore(new FileInputStream(BACKUPDIR + "/fullBackup.zip"), null, null, null);

    Assert.assertEquals(database2.countClass("TestBackup"), 1);

    database2.close();
  }

  // @Test
  public void testAutomaticBackupDisable()
      throws IOException,
      ClassNotFoundException,
      MalformedObjectNameException,
      InstanceAlreadyExistsException,
      NotCompliantMBeanException,
      MBeanRegistrationException {

    var jsonConfig =
        IOUtils.readStreamAsString(getClass().getResourceAsStream("automatic-backup.json"));

    var tx = db.begin();
    var doc = ((EntityImpl) tx.newEntity());
    doc.updateFromJSON(jsonConfig);

    doc.setProperty("enabled", false);
    doc.setProperty("targetFileName", "${DBNAME}.zip");

    doc.setProperty("dbExclude", new String[]{"testautobackup"});

    doc.setPropertyInChain(
        "firstTime",
        new SimpleDateFormat("HH:mm:ss").format(new Date(System.currentTimeMillis() + 2000)));

    IOUtils.writeFile(new File(tempDirectory + "/config/automatic-backup.json"), doc.toJSON());

    final var aBackup = new AutomaticBackup();

    final var config = new ServerParameterConfiguration[]{};

    try {
      aBackup.config(server, config);

    } catch (ConfigurationException e) {
      Assert.fail("It should not get an configuration exception when disabled");
    }
  }

  /// / @Test
  // //TODO: move to EE test suite
  // public void testIncrementalBackup() throws IOException, ClassNotFoundException,
  // MalformedObjectNameException,
  // InstanceAlreadyExistsException, NotCompliantMBeanException, MBeanRegistrationException {
  // if (new File(BACKUPDIR + "/" + DBNAME).exists())
  // FileUtils.deleteRecursively(new File(BACKUPDIR + "/" + DBNAME));
  //
  // final AutomaticBackup aBackup = new AutomaticBackup();
  //
  // final ServerParameterConfiguration[] config = new ServerParameterConfiguration[] {
  // new ServerParameterConfiguration("firstTime",
  // new SimpleDateFormat("HH:mm:ss").format(new Date(System.currentTimeMillis() + 2000))),
  // new ServerParameterConfiguration("delay", "1d"), new ServerParameterConfiguration("mode",
  // "INCREMENTAL_BACKUP"),
  // new ServerParameterConfiguration("target.directory", BACKUPDIR) };
  //
  // aBackup.config(server, config);
  //
  // try {
  // Thread.sleep(5000);
  // } catch (ThreadInterruptedException e) {
  // e.printStackTrace();
  // }
  //
  // aBackup.sendShutdown();
  //
  // final DatabaseDocumentTx database2 = new DatabaseDocumentTx(URL2);
  // if (database2.exists())
  // database2.open("admin", "admin").drop();
  // database2.create(BACKUPDIR + "/" + DBNAME);
  //
  // Assert.assertEquals(database2.countClass("TestBackup"), 1);
  // }

  // @Test
  public void testExport() throws Exception {
    if (new File(BACKUPDIR + "/fullExport.json.gz").exists()) {
      new File(BACKUPDIR + "/fullExport.json.gz").delete();
    }

    final var aBackup = new AutomaticBackup();

    final var config =
        new ServerParameterConfiguration[]{
            new ServerParameterConfiguration("enabled", "true"),
            new ServerParameterConfiguration(
                "firstTime",
                new SimpleDateFormat("HH:mm:ss").format(
                    new Date(System.currentTimeMillis() + 5000))),
            new ServerParameterConfiguration("delay", "1d"),
            new ServerParameterConfiguration("mode", "EXPORT"),
            new ServerParameterConfiguration("target.directory", BACKUPDIR),
            new ServerParameterConfiguration("target.fileName", "fullExport.json.gz")
        };

    aBackup.config(server, config);

    waitForFile(Paths.get(BACKUPDIR).resolve("fullExport.json.gz"));

    aBackup.sendShutdown();

    if (server.existsDatabase(DBNAME3)) {
      server.dropDatabase(DBNAME3);
    }
    server
        .getContext()
        .execute(
            "create database ? disk users (admin identified by 'admin' role admin)", DBNAME3);

    var database2 = server.getDatabases().openNoAuthorization(DBNAME3);

    new DatabaseImport(database2, BACKUPDIR + "/fullExport.json.gz", null).importDatabase();

    Assert.assertEquals(database2.countClass("TestBackup"), 1);

    database2.close();
  }

  private void waitForFile(Path path) throws InterruptedException {
    var startTs = System.currentTimeMillis();

    while (!Files.exists(path)) {
      Thread.sleep(1000);

      if ((System.currentTimeMillis() - startTs) > 1000 * 60 * 20) {
        break;
      }
    }
  }
}
