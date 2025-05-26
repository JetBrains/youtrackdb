package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.auto.loader.ChildFirstClassLoader;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseCompare;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import org.testng.annotations.Test;

public class BaseDBCompatibilityTest {

  @Test
  public void shouldLoadOldDb() throws Exception {
    // Load version 1
//        "file:/Users/logart/dev/youtrackdb/core/target/youtrackdb-core-1.0.0-SNAPSHOT.jar");
    var legacySession = loadSession(
        "file:/Users/logart/dev/youtrackdb/core/target/youtrackdb-core-1.0.0-SNAPSHOT.jar",
        "file:/Users/logart/dev/youtrackdb/client/target/youtrackdb-client-1.0.0-SNAPSHOT.jar"
    );
    var currentSession = loadSession(
        "file:/Users/logart/dev/youtrackdb/core/target/youtrackdb-core-1.0.0-SNAPSHOT.jar",
        "file:/Users/logart/dev/youtrackdb/client/target/youtrackdb-client-1.0.0-SNAPSHOT.jar"
    );
    var compare = new DatabaseCompare(
        legacySession,
        currentSession,
        null
    );
    compare.compare();
  }

  @SuppressWarnings("unchecked")
  private static <T extends DatabaseSessionEmbedded> T loadSession(String... jarUrls)
      throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
    var jars = new URL[jarUrls.length];
    for (var i = 0; i < jarUrls.length; i++) {
      jars[i] = new URL(jarUrls[i]);
    }
    var loader = new ChildFirstClassLoader(jars, ClassLoader.getSystemClassLoader());

    var configClass = loader.loadClass("com.jetbrains.youtrack.db.api.config.YouTrackDBConfig");
    var configBuilderClass = loader.loadClass(
        "com.jetbrains.youtrack.db.api.config.YouTrackDBConfigBuilder");
    var ytdbClass = loader.loadClass("com.jetbrains.youtrack.db.internal.core.db.YouTrackDBAbstract");
    var youtracks = loader.loadClass("com.jetbrains.youtrack.db.api.YourTracks");

    var configBuilderFactoryMethod = configClass.getDeclaredMethod("builder");
    var configBuilderBuildMethod = configBuilderClass.getDeclaredMethod("build");
    var configBuilder = configBuilderClass.cast(configBuilderFactoryMethod.invoke(null));

    var embeddedMethod = youtracks.getDeclaredMethod("embedded", String.class, configClass);

    var ytdbImpl = embeddedMethod.invoke(null, "mydb",
        configBuilderBuildMethod.invoke(configBuilder));
    var openMethod = ytdbClass.getDeclaredMethod("open", String.class, String.class, String.class);
    var result = openMethod.invoke(ytdbImpl, "mydb", "admin", "admin");
    return (T) result;
  }
}
