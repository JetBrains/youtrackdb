package com.jetbrains.youtrack.db.auto;

import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.CommonDownloader;
import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.JarDownloader;
import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.JarDownloader.LocationType;
import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.github.GithubJarBuilder;
import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.github.GithubRepoDownloader;
import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.github.MavenBuilder;
import com.jetbrains.youtrack.db.auto.binarycompat.fetcher.maven.MavenJarDownloader;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.testng.annotations.Test;

public class BaseDBCompatibilityTest {

  private CommonDownloader downloader = new CommonDownloader(Map.of(
      LocationType.GIT, new GithubJarBuilder(new GithubRepoDownloader(), new MavenBuilder()),
      LocationType.MAVEN, new MavenJarDownloader("./local-repo")
  ));

  @Test
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
    var test = new BaseDBCompatibilityTest();
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
            dbMetadata.name(), importVersion.name());

        var importMetadata = new DbMetadata(dbMetadata.name(),
            dbMetadata.location() + "___import" + importVersion.name(),
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
                //do nothing
                unused -> {
                }
            );
            assertTrue(compare.compare());

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
      throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
    var session = loadSession(dbMetadata, version);
    var loader = session.loader();

    var exportDbClass = loader.loadClass(
        "com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport");
    var commandOutputListenerClass = loader.loadClass(
        "com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener");
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
    var importJar = downloader.prepareArtifact(
        version.location().type(),
        version.location().source(),
        version.name()
    );
    var loader = new URLClassLoader(new URL[]{importJar.toURL()},
        ClassLoader.getSystemClassLoader());
    var configClass = loader.loadClass("com.jetbrains.youtrack.db.api.config.YouTrackDBConfig");
    var configBuilderClass = loader.loadClass(
        "com.jetbrains.youtrack.db.api.config.YouTrackDBConfigBuilder");
    var ytdbClass = loader.loadClass(
        "com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract");
    var youtracks = loader.loadClass("com.jetbrains.youtrack.db.api.YourTracks");

    var configBuilderFactoryMethod = configClass.getDeclaredMethod("builder");
    var configBuilderBuildMethod = configBuilderClass.getDeclaredMethod("build");
    var configBuilder = configBuilderClass.cast(configBuilderFactoryMethod.invoke(null));

    var embeddedMethod = youtracks.getDeclaredMethod("embedded", String.class, configClass);

    var ytdbImpl = embeddedMethod.invoke(null, dbMetadata.location(),
        configBuilderBuildMethod.invoke(configBuilder));
    var openMethod = ytdbClass.getDeclaredMethod("open", String.class, String.class, String.class);
    var session = openMethod.invoke(ytdbImpl, dbMetadata.name(), "admin", "admin");

    var databaseSessionEmbeddedClass = loader.loadClass(
        "com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded");
    return new SessionLoadMetadata(loader, ytdbClass, ytdbImpl, databaseSessionEmbeddedClass,
        session);
  }

  private static void closeSession(SessionLoadMetadata session)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    var sessionCloseMethod = session.sessionClass().getDeclaredMethod("close");
    sessionCloseMethod.invoke(session.session());
    var closeMethod = session.ytdbClass().getDeclaredMethod("close");
    closeMethod.invoke(session.ytdb());
  }

  private List<TestPlan> loadTestPlan() throws IOException {
    var mapper = new ObjectMapper(new YAMLFactory());
    var input = BaseDBCompatibilityTest.class.getClassLoader()
        .getResourceAsStream("binary-compatibility-test-config.yaml");
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

  private record DbMetadata(String name, String location, String user, String password) {

  }
}
