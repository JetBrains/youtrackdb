package com.jetbrains.youtrack.db.internal.lucene.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.common.io.FileUtils;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.server.ServerMain;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneIndexCrashRestoreIT {

  private AtomicLong idGen;

  private ExecutorService executorService;
  private Process serverProcess;
  private List<String> names;
  private List<String> surnames;
  private YouTrackDBImpl youTrackDB;
  private SessionPool sessionPool;
  private static final String BUILD_DIRECTORY = "./target/testLuceneCrash";

  @Before
  public void beforeMethod() throws Exception {
    executorService = Executors.newCachedThreadPool();
    idGen = new AtomicLong();
    spawnServer();

    youTrackDB =
        new YouTrackDBImpl("remote:localhost:3900", "root", "root",
            YouTrackDBConfig.defaultConfig());
    youTrackDB.execute(
        "create database testLuceneCrash plocal users (admin identified by 'admin' role admin)");

    sessionPool = new SessionPoolImpl(youTrackDB, "testLuceneCrash", "admin", "admin");

    // names to be used for person to be indexed
    names =
        Arrays.asList(
            "John",
            "Robert Luis",
            "Jane",
            "andrew",
            "Scott",
            "luke",
            "Enriquez",
            "Luis",
            "Gabriel",
            "Sara");
    surnames =
        Arrays.asList(
            "Smith", "Done", "Doe", "pig", "mole", "Jones", "Candito", "Simmons", "Angel", "Low");
  }

  public void spawnServer() throws Exception {
    LogManager.instance().installCustomFormatter();
    GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.setValue(1000000);
    GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(3);
    GlobalConfiguration.FILE_LOCK.setValue(false);

    final var buildDir = new File(BUILD_DIRECTORY);
    if (buildDir.exists()) {
      FileUtils.deleteRecursively(buildDir);
    }

    buildDir.mkdirs();

    final var mutexFile = new File(buildDir, "mutex.ct");
    final var mutex = new RandomAccessFile(mutexFile, "rw");
    mutex.seek(0);
    mutex.write(0);

    var javaExec = System.getProperty("java.home") + "/bin/java";
    javaExec = new File(javaExec).getCanonicalPath();

    var processBuilder =
        new ProcessBuilder(
            javaExec,
            "-Xmx2048m",
            "-classpath",
            System.getProperty("java.class.path"),
            "-DmutexFile=" + mutexFile.getAbsolutePath(),
            "-DYOUTRACKDB_HOME=" + BUILD_DIRECTORY,
            RemoteDBRunner.class.getName());

    processBuilder.inheritIO();
    serverProcess = processBuilder.start();

    var started = false;
    do {
      System.out.println(": Wait for server start");
      TimeUnit.SECONDS.sleep(5);
      mutex.seek(0);
      started = mutex.read() == 1;
    } while (!started);

    mutex.close();
    mutexFile.delete();
    System.out.println(": Server was started");
  }

  @After
  public void tearDown() {
    var buildDir = new File("./target/databases");
    FileUtils.deleteRecursively(buildDir);
    Assert.assertFalse(buildDir.exists());
  }

  @Test
  public void testEntriesAddition() throws Exception {
    List<DataPropagationTask> futures = new ArrayList<>();
    DatabaseSessionInternal db;
    ResultSet res;
    try {
      createSchema(sessionPool);

      for (var i = 0; i < 1; i++) {
        // first round
        System.out.println("Start data propagation ::" + i);

        futures = startLoaders();

        System.out.println("Wait for 1 minutes");
        TimeUnit.MINUTES.sleep(1);
        System.out.println("Stop loaders");
        stopLoaders(futures);

        System.out.println("Wait for 30 seconds");
        TimeUnit.SECONDS.sleep(30);

        db = (DatabaseSessionInternal) sessionPool.acquire();
        // wildcard will not work
        res = db.query("select from Person where name lucene 'Robert' ");
        assertThat(res).hasSize(0);
        res.close();

        // plain name fetch docs
        res = db.query("select from Person where name lucene 'Robert Luis' LIMIT 20");
        assertThat(res).hasSize(20);
        res.close();
        db.close();
        System.out.println("END data propagation ::" + i);
      }
    } finally {
      // crash the server

      serverProcess.destroyForcibly();

      serverProcess.waitFor();
      // crash the server
    }

    System.out.println("Process was CRASHED");

    System.out.println("stop loaders");
    stopLoaders(futures);

    System.out.println("All loaders done");

    sessionPool.close();
    youTrackDB.close();

    // now we start embedded
    System.out.println("START AGAIN");

    // start embedded
    var server = ServerMain.create(true);
    server.setServerRootDirectory(BUILD_DIRECTORY);

    var conf = RemoteDBRunner.class.getResourceAsStream("index-crash-config.xml");

    server.startup(conf);
    server.activate();

    while (!server.isActive()) {
      System.out.println("server active = " + server.isActive());
      TimeUnit.SECONDS.sleep(1);
    }

    youTrackDB =
        new YouTrackDBImpl("remote:localhost:3900", "root", "root",
            YouTrackDBConfig.defaultConfig());
    sessionPool = new SessionPoolImpl(youTrackDB, "testLuceneCrash", "admin", "admin");

    // test query
    db = (DatabaseSessionInternal) sessionPool.acquire();
    db.getMetadata().reload();

    var index = db.getMetadata().getIndexManagerInternal().getIndex(db, "Person.name");
    assertThat(index).isNotNull();

    // sometimes the metadata is null!!!!!
    assertThat(index.getMetadata()).isNotNull();

    assertThat(index.getMetadata().get("default")).isNotNull();
    assertThat(index.getMetadata().get("default"))
        .isEqualTo("org.apache.lucene.analysis.core.KeywordAnalyzer");
    assertThat(index.getMetadata().get("unknownKey")).isEqualTo("unknownValue");

    // sometimes it is not null, and all works fine
    res = db.query("select from Person where name lucene 'Robert' ");

    assertThat(res).hasSize(0);
    res.close();
    res = db.query("select from Person where name lucene 'Robert Luis' LIMIT 20");

    assertThat(res).hasSize(20);
    res.close();
    db.close();
    // shutdown embedded
    server.shutdown();
  }

  private void stopLoaders(List<DataPropagationTask> futures) {
    for (var future : futures) {

      future.stop();
    }
  }

  private List<DataPropagationTask> startLoaders() {
    List<DataPropagationTask> futures = new ArrayList<>();
    for (var i = 0; i < 4; i++) {
      final var loader = new DataPropagationTask(sessionPool);
      executorService.submit(loader);
      futures.add(loader);
    }
    return futures;
  }

  private void createSchema(SessionPool pool) {

    final var db = (DatabaseSessionInternal) pool.acquire();

    System.out.println("create index for db:: " + db.getURL());
    db.execute("Create class Person");
    db.execute("Create property Person.name STRING");
    db.execute("Create property Person.surname STRING");
    db.execute(
        "Create index Person.name on Person(name) FULLTEXT ENGINE LUCENE METADATA"
            + " {'default':'org.apache.lucene.analysis.core.KeywordAnalyzer',"
            + " 'unknownKey':'unknownValue'}");
    db.execute(
        "Create index Person.surname on Person(surname) FULLTEXT ENGINE LUCENE METADATA"
            + " {'default':'org.apache.lucene.analysis.core.KeywordAnalyzer',"
            + " 'unknownKey':'unknownValue'}");
    db.getMetadata().getIndexManagerInternal().reload(db);

    System.out.println(
        db.getMetadata()
            .getIndexManagerInternal()
            .getIndex(db, "Person.name")
            .getConfiguration(db));
    db.close();
  }

  public static final class RemoteDBRunner {

    public static void main(String[] args) throws Exception {
      //      System.out.println("prepare server");
      GlobalConfiguration.RID_BAG_EMBEDDED_TO_SBTREEBONSAI_THRESHOLD.setValue(3);
      GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.setValue(100000000);

      //      System.out.println("create server instance");
      var server = ServerMain.create();
      var conf = RemoteDBRunner.class.getResourceAsStream("index-crash-config.xml");

      LogManager.instance().installCustomFormatter();
      server.startup(conf);
      server.activate();

      final var mutexFile = System.getProperty("mutexFile");
      //      System.out.println("mutexFile = " + mutexFile);

      final var mutex = new RandomAccessFile(mutexFile, "rw");
      mutex.seek(0);
      mutex.write(1);
      mutex.close();
    }
  }

  public class DataPropagationTask implements Callable<Void> {

    private final SessionPool pool;

    private volatile boolean stop;

    public DataPropagationTask(SessionPool pool) {
      stop = false;
      this.pool = pool;
    }

    public void stop() {
      stop = true;
    }

    @Override
    public Void call() throws Exception {

      DatabaseSession testDB = null;
      try {
        testDB = pool.acquire();
        while (!stop) {
          var id = idGen.getAndIncrement();
          var ts = System.currentTimeMillis();

          if (id % 1000 == 0) {
            System.out.println(Thread.currentThread().getName() + " inserted:: " + id);
            testDB.commit();
          }
          if (id % 2000 == 0) {
            final var resultSet =
                testDB.execute("delete from Person where name lucene 'Robert' ");
            System.out.println(
                Thread.currentThread().getName()
                    + " deleted:: "
                    + resultSet.next().getProperty("count"));
            testDB.commit();
          }
          var nameIdx = (int) (id % names.size());

          for (var i = 0; i < 10; i++) {
            if (id % 1000 == 0) {
              var insert = "insert into person (name) values ('" + names.get(nameIdx) + "')";
              testDB.execute(insert).close();
            } else {
              var insert =
                  "insert into person (name,surname) values ('"
                      + names.get(nameIdx)
                      + "','"
                      + surnames.get(nameIdx)
                      + "')";
              testDB.execute(insert).close();
            }
          }
        }
      } catch (Exception e) {
        throw e;
      } finally {
        if (testDB != null && !testDB.isClosed()) {
          testDB.close();
        }
      }

      return null;
    }
  }
}
