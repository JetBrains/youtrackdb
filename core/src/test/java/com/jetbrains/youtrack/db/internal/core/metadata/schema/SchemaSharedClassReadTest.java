package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.common.SessionPool;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import org.junit.Test;

public class SchemaSharedClassReadTest extends DbTestBase {

  @Test
  public void testRereadClassAfterModification() {

    Schema schema = session.getMetadata().getSchema();

    var originalClass = schema.createClass("zurich");
    var loadedClass = schema.getClass("zurich");
    originalClass.createProperty("modification", PropertyType.STRING);
    var reloadedClass = schema.getClass("zurich");
    var loadedClassDelegate = extractDelegate(loadedClass);
    var reloadedClassDelegate = extractDelegate(reloadedClass);
    var originalClassDelegate = extractDelegate(originalClass);
    assertThat(reloadedClassDelegate)
        .as("Reloaded class reference is " + System.identityHashCode(reloadedClass)
            + " should be the same as the original one " + System.identityHashCode(originalClass))
        .isSameAs(originalClassDelegate);
    assertSame(originalClassDelegate, loadedClassDelegate);
    assertSame(originalClassDelegate, reloadedClassDelegate);

    assertEquals(PropertyType.STRING, reloadedClass.getProperty("modification").getType());
  }

  private SchemaClassImpl extractDelegate(SchemaClass proxy) {
    try {
      var field = proxy.getClass().getSuperclass().getDeclaredField("delegate");
      field.setAccessible(true);
      return (SchemaClassImpl) field.get(proxy);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testReadPropertiesFromStorage() {
    Schema schema = session.getMetadata().getSchema();

    var classWithProperty = schema.createClass("propertizedClass");
    classWithProperty.createProperty("prop1", PropertyType.STRING);

    // we need db reload to force reread schema from storage,
    // I am working on a bug where in-memory schema is fine, but after reload class properties are empty
    var storage = session.getStorage();
    storage.reload(session);
    session.getMetadata().reload();
    var anotherSchema = session.getMetadata().getSchema();

    var coldClass = anotherSchema.getClass("propertizedClass");
    assertNotNull(coldClass.getProperty("prop1"));
  }

  @Test
  public void testReadPropertiesFromStorage2() {
    final YouTrackDB youTrackDb =
        new YouTrackDBImpl(YouTrackDBInternal.embedded(
            getBaseDirectoryPath(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 1)
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build()
        ));
    if (!youTrackDb.exists("test")) {
      youTrackDb.execute(
          "create database "
              + "test"
              + " "
              + "memory"
              + " users ( admin identified by '"
              + CreateDatabaseUtil.NEW_ADMIN_PASSWORD
              + "' role admin)");
    }
    DatabaseSessionInternal db2 = (DatabaseSessionInternal) youTrackDb.open("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    db2.activateOnCurrentThread();
    db2.execute("create class propertizedClass");
    db2.execute("create property propertizedClass.prop1 STRING");
    final SessionPool pool =
        youTrackDb.cachedPool("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var session = (DatabaseSessionInternal) pool.acquire();
    SchemaClass coldClass = session.getMetadata().getSchema().getClass("propertizedClass");
    assertEquals(coldClass.getProperty("prop1").getType(), PropertyType.STRING);

    youTrackDb.close();
  }
}
