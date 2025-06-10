package com.jetbrains.youtrack.db.auto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
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
import java.util.List;
import org.testng.annotations.Test;

public class BaseDBCompatibilityTest {

  @Test
  public void shouldLoadOldDb() throws Exception {
    var testPlans = loadTestPlan();

    for (var testPlan : testPlans) {
      for (var dbMetadata : testPlan.dbs()) {
        testBinaryCompatibility(dbMetadata, testPlan.versions());
      }
    }
  }

  private void testBinaryCompatibility(DbMetadata dbMetadata, List<VersionInfo> versions)
      throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
    LogManager.instance().info(
        this,
        "Testing db {} binary compatibility with versions: {}",
        dbMetadata.name(),
        versions
    );
    var currentExportVersionIndex = 0;
    // no need to test last version, since there will be no further version to compare to
    while (currentExportVersionIndex < versions.size() - 1) {
      var exportVersion = versions.get(currentExportVersionIndex);

      var jsonExportPath = exportDbWithVersion(
          dbMetadata,
          exportVersion
      );
      for (var i = currentExportVersionIndex + 1; i < versions.size(); i++) {
        var importVersion = versions.get(i);
        LogManager.instance().info(
            this,
            "Testing db {} exported with version {} binary compatibility with version: {}",
            dbMetadata.name(), importVersion.name());

        try (var importSession = getSession(
            dbMetadata.location() + "___import" + importVersion.name(),
            dbMetadata.name(),
            dbMetadata.user(), dbMetadata.password())) {

          var dbImport = new DatabaseImport(importSession, jsonExportPath, null);
          dbImport.importDatabase();
          // open old binary db with new version
          try (var binarySession = getSession(dbMetadata.location(), dbMetadata.name(),
              dbMetadata.user(), dbMetadata.password())) {
            // compare imported and opened dbs
            var compare = new DatabaseCompare(
                importSession,
                binarySession,
                //do nothing
                unused -> {
                }
            );
            compare.compare();
          }
        }
      }
    }
  }

  private DatabaseSessionEmbedded getSession(String dbPath, String dbName, String user,
      String password) {
    var configBuilder = YouTrackDBConfig.builder();

    var embedded = YourTracks.embedded(dbPath, configBuilder.build());
    embedded.createIfNotExists(dbName, DatabaseType.DISK, user, password, "admin");
    return (DatabaseSessionEmbedded) embedded.open(dbName, user, password);
  }

  private String exportDbWithVersion(DbMetadata dbMetadata, VersionInfo version)
      throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
    var jarUrls = version.jars();
    var jars = new URL[jarUrls.length];
    for (var i = 0; i < jarUrls.length; i++) {
      jars[i] = new URL(jarUrls[i]);
    }
    var loader = new URLClassLoader(jars, ClassLoader.getSystemClassLoader());

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

    var exportDbClass = loader.loadClass(
        "com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport");
    var databaseSessionEmbeddedClass = loader.loadClass(
        "com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded");
    var commandOutputListenerClass = loader.loadClass(
        "com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener");
    var exportDbMethod = exportDbClass.getDeclaredMethod("run");
    var exportDbObjectConstructor = exportDbClass.getDeclaredConstructor(
        databaseSessionEmbeddedClass, String.class, commandOutputListenerClass);

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
    var exportPath =
        System.getProperty("user.dir") + "/target/" + dbMetadata.name() + "___" + version.name()
            + ".json";
    var exportDbObject = exportDbObjectConstructor.newInstance(session, exportPath,
        noOpListener);
    exportDbMethod.invoke(exportDbObject, (Object[]) null);
    var sessionCloseMethod = databaseSessionEmbeddedClass.getDeclaredMethod("close");
    sessionCloseMethod.invoke(session);
    var closeMethod = ytdbClass.getDeclaredMethod("close");
    closeMethod.invoke(ytdbImpl);
    return exportPath + ".gz";
  }

  @SuppressWarnings("unchecked")
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

  private TestPlan parseTestPlan(ObjectMapper mapper, JsonNode run) {
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

  private record VersionInfo(String name, String[] jars) {

  }

  private record DbMetadata(String name, String location, String user, String password) {

  }
}
