package com.jetbrains.youtrackdb.auto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.CommonDownloader;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.JarDownloader;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.JarDownloader.LocationType;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github.GithubJarBuilder;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github.GithubRepoDownloader;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.github.MavenBuilder;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.maven.MavenJarDownloader;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.data.CommonDbDownloader;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.data.CommonDbDownloader.DbLocationInfo;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.data.CommonDbDownloader.DbLocationType;
import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.data.CommonDbDownloader.DbMetadata;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

public class BaseDBCompatibilityChecker {

  private final CommonDownloader downloader = new CommonDownloader(Map.of(
      LocationType.GIT, new GithubJarBuilder(new GithubRepoDownloader(), new MavenBuilder()),
      LocationType.MAVEN, new MavenJarDownloader("/tmp/youtrackdb/binarycompat/local-repo")
  ));
  private final CommonDbDownloader dbDownloader = new CommonDbDownloader();

  public void shouldLoadOldDb() throws Exception {
    var testPlans = loadTestPlan();

    for (var testPlan : testPlans) {
      for (var dbMetadata : testPlan.dbs()) {
        testBinaryCompatibility(dbMetadata, testPlan.versions());
      }
    }
  }

  //this method is needed to run this test externally from terminal
  public static void main(String[] args) throws Exception {
    var test = new BaseDBCompatibilityChecker();
    // inject jars into test
    test.shouldLoadOldDb();
  }

  private void testBinaryCompatibility(DbMetadata dbMetadata, List<VersionInfo> versions)
      throws Exception {
    LogManager.instance().info(
        this,
        "Testing db %s binary compatibility with versions: %s",
        dbMetadata.name(),
        versions
    );
    var currentExportVersionIndex = 0;
    // no need to test last version, since there will be no further version to compare to
    while (currentExportVersionIndex < versions.size() - 1) {
      var exportVersion = versions.get(currentExportVersionIndex);
      var jsonExportPath = exportDbWithVersion(dbMetadata, exportVersion);
      for (var i = currentExportVersionIndex + 1; i < versions.size(); i++) {
        var importVersion = versions.get(i);
        LogManager.instance().info(
            this,
            "Testing db %s exported with version %s binary compatibility with version: %s",
            dbMetadata.name(), exportVersion.name(), importVersion.name());

        var importDirectory = Files.createTempDirectory(
            "ytdb-import" + dbMetadata.name() + "___" + importVersion.name());
        var importMetadata = new DbMetadata(dbMetadata.name(),
            new DbLocationInfo(importDirectory.toAbsolutePath().toString(), DbLocationType.FILE),
            dbMetadata.user(), dbMetadata.password()
        );
        var importSession = loadSession(importMetadata, importVersion);
        try {
          var dbImport = new DatabaseImport((DatabaseSessionEmbedded) importSession.session(),
              jsonExportPath, null);
          dbImport.importDatabase();

          // open old binary db with new version
          var newSessionOnOldDb = loadSession(dbMetadata, importVersion);
          try {
            // compare imported and opened dbs
            var compare = new DatabaseCompare(
                (DatabaseSessionEmbedded) importSession.session(),
                (DatabaseSessionEmbedded) newSessionOnOldDb.session(),
                Map.of("ouser", Set.of("password")),
                msg -> {
                  LogManager.instance().info(this, msg);
                }
            );
            assert compare.compare() :
                "Db " + dbMetadata + ". Opened with version " + importVersion
                    + " is not compatible to db exported with version "
                    + exportVersion + " and reimported with version " + importVersion;
          } finally {
            closeSession(newSessionOnOldDb);
          }
        } finally {
          closeSession(importSession);
        }
      }
      currentExportVersionIndex++;
    }
  }

  private String exportDbWithVersion(DbMetadata dbMetadata, VersionInfo version)
      throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
    var session = loadSession(dbMetadata, version);
    var loader = session.loader();

    var exportDbClass = loader.loadClass(
        "com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseExport");
    var commandOutputListenerClass = loader.loadClass(
        "com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener");
    var exportDbMethod = exportDbClass.getDeclaredMethod("run");
    var exportDbObjectConstructor = exportDbClass.getDeclaredConstructor(
        session.sessionClass, String.class, commandOutputListenerClass);

    var noOpListener = Proxy.newProxyInstance(
        commandOutputListenerClass.getClassLoader(),
        new Class<?>[]{commandOutputListenerClass},
        (Object proxy, Method m, Object[] args) -> {
          if (m.getName().equals("onMessage")) {
            return null;
          }
          // Handle Object methods like toString, hashCode, equals
          return m.invoke(this, args);
        }
    );
    var exportDir = Files.createTempDirectory(
        "ytdb-export" + dbMetadata.name() + "___" + version.name());
    var exportPath = exportDir.toAbsolutePath().toString() + ".json";
    var exportDbObject = exportDbObjectConstructor.newInstance(session.session(), exportPath,
        noOpListener);
    exportDbMethod.invoke(exportDbObject, (Object[]) null);
    closeSession(session);
    return exportPath + ".gz";
  }

  @Nonnull
  private SessionLoadMetadata loadSession(DbMetadata dbMetadata, VersionInfo version)
      throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, MalformedURLException {
    var importJars = downloader.prepareArtifact(
        version.location().type(),
        version.location().source(),
        version.name()
    );
    // this class loader "sees", all the classes from the current class loader which could issues,
    // in case some classes are removed in the newer version of the code, but still present in the older one
    // but without it, I will need a mechanism to load all the db dependencies,
    // without specifying them manually one more time in the db compatibility test
    // this is an open question for now.
    // to deliver test sooner I will leave it as is with this comment
    var loader = new URLClassLoader(new URL[]{importJars.toURI().toURL()});
    var ytdbClass = loader.loadClass(
        "com.jetbrains.youtrackdb.internal.core.db.YouTrackDBAbstract");
    var youtracks = loader.loadClass("com.jetbrains.youtrackdb.api.YourTracks");

    var embeddedMethod = youtracks.getDeclaredMethod("instance", String.class);

    var location = dbDownloader.prepareDbLocation(dbMetadata);
    var ytdbImpl = embeddedMethod.invoke(null, location);

    var existsMethod = ytdbClass.getDeclaredMethod("exists", String.class);
    if (!(boolean) existsMethod.invoke(ytdbImpl, dbMetadata.name())) {
      var createMethod = ytdbClass.getDeclaredMethod("create", String.class, DatabaseType.class,
          String[].class);
      createMethod.invoke(ytdbImpl, dbMetadata.name(), DatabaseType.DISK,
          new String[]{dbMetadata.user(), dbMetadata.password(), "admin"});
    }
    var openMethod = ytdbClass.getDeclaredMethod("open", String.class, String.class, String.class);
    var session = openMethod.invoke(ytdbImpl, dbMetadata.name(), "admin", "admin");

    var databaseSessionEmbeddedClass = loader.loadClass(
        "com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded");
    return new SessionLoadMetadata(loader, ytdbClass, ytdbImpl, databaseSessionEmbeddedClass,
        session);
  }

  private static void closeSession(SessionLoadMetadata session)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    var sessionCloseMethod = session.sessionClass().getDeclaredMethod("close");
    if (session.session() != null) {
      sessionCloseMethod.invoke(session.session());
    }
    var closeMethod = session.ytdbClass().getDeclaredMethod("close");
    if (session.ytdb() != null) {
      closeMethod.invoke(session.ytdb());
    }
  }

  private List<TestPlan> loadTestPlan() throws IOException {
    var mapper = new ObjectMapper(new YAMLFactory());
    var testPlanLocation = System.getenv("YTDB_BINARY_COMPAT_TEST_CONFIG");
    InputStream input;
    if (testPlanLocation == null) {
      input = BaseDBCompatibilityChecker.class.getClassLoader()
          .getResourceAsStream("binary-compatibility-test-config.yaml");
    } else {
      input = Files.newInputStream(Path.of(testPlanLocation));
    }
    if (input == null) {
      throw new IllegalStateException(
          "Test config not found. Please provide path to it in YTDB_BINARY_COMPAT_TEST_CONFIG environment variable");
    }
    var tree = mapper.readTree(input);
    var runs = tree.get("runs");
    return runs.valueStream()
        .map(t -> parseTestPlan(mapper, t))
        .toList();
  }

  private static TestPlan parseTestPlan(ObjectMapper mapper, JsonNode run) {
    try {
      var dbs = mapper.treeToValue(run.get("dbs"),
          new TypeReference<List<DbMetadata>>() {
          }
      );
      var versions = mapper.treeToValue(run.get("versions"),
          new TypeReference<List<VersionInfo>>() {
          }
      );
      return new TestPlan(dbs, versions);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to parse test plan", e);
    }
  }

  private record TestPlan(List<DbMetadata> dbs, List<VersionInfo> versions) {

  }

  private record SessionLoadMetadata(
      ClassLoader loader,
      Class<?> ytdbClass,
      Object ytdb,
      Class<?> sessionClass,
      Object session
  ) {

  }

  private record LocationInfo(String source, JarDownloader.LocationType type) {

  }

  private record VersionInfo(String name, LocationInfo location) {

  }
}
