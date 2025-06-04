package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import org.testng.annotations.Test;

public class BaseDBCompatibilityTest {

  @Test
  public void shouldLoadOldDb() throws Exception {
    // load old binary db and export old db to json with old db version
    var jsonExportPath = "/Users/logart/dev/youtrackdb/tests/target/test-db.json.gz";

    var oldBinaryDbPath = "/Users/logart/dev/youtrackdb/tests/target/test-db";
    var currentBinaryDbPath = "/Users/logart/dev/youtrackdb/tests/target/test-db-current";
    var dbName = "DbCreationTest";
    var user = "admin";
    var password = "admin";
    var dbMetadata = new DbMetadata(oldBinaryDbPath, currentBinaryDbPath, dbName);

    exportDbWithVersion(
        dbMetadata,
        "file:/Users/logart/dev/youtrackdb/core/target/youtrackdb-core-1.0.0-SNAPSHOT.jar",
        "file:/Users/logart/dev/youtrackdb/client/target/youtrackdb-client-1.0.0-SNAPSHOT.jar"
    );
    // import exported version with new db version
    var importSession = getSession(dbMetadata.currentDbPath(), dbName, user, password);
    var dbImport = new DatabaseImport(importSession, jsonExportPath, null);
    dbImport.importDatabase();
    // open old binary db with new version
    var binarySession = getSession(dbMetadata.oldDbPath(), dbName, user, password);
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

  private DatabaseSessionEmbedded getSession(String dbPath, String dbName, String user,
      String password) {
    var configBuilder = YouTrackDBConfig.builder();

    var embedded = YourTracks.embedded(dbPath, configBuilder.build());
    embedded.createIfNotExists(dbName, DatabaseType.DISK, user, password, "admin");
    return (DatabaseSessionEmbedded) embedded.open(dbName, user, password);
  }

  private void exportDbWithVersion(DbMetadata dbMetadata, String... jarUrls)
      throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {
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

    var ytdbImpl = embeddedMethod.invoke(null, dbMetadata.oldDbPath(),
        configBuilderBuildMethod.invoke(configBuilder));
    var openMethod = ytdbClass.getDeclaredMethod("open", String.class, String.class, String.class);
    var session = openMethod.invoke(ytdbImpl, dbMetadata.dbName(), "admin", "admin");

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
    var exportDbObject = exportDbObjectConstructor.newInstance(session, dbMetadata.oldDbPath(),
        noOpListener);
    exportDbMethod.invoke(exportDbObject, (Object[]) null);
    var sessionCloseMethod = databaseSessionEmbeddedClass.getDeclaredMethod("close");
    sessionCloseMethod.invoke(session);
    var closeMethod = ytdbClass.getDeclaredMethod("close");
    closeMethod.invoke(ytdbImpl);
  }

  private record DbMetadata(String oldDbPath, String currentDbPath, String dbName) {

  }
}
