package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.SessionPool;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.Test;

public class SchemaSharedClassReadTest extends DbTestBase {

  @Test
  public void testRereadClassAfterModification() {

    Schema schema = session.getMetadata().getSchema();

    SchemaClass originalClass = schema.createClass("zurich");
    SchemaClass loadedClass = schema.getClass("zurich");
    originalClass.createProperty("modification", PropertyType.STRING);
    SchemaClass reloadedClass = schema.getClass("zurich");
    assertThat(reloadedClass)
        .as("Reloaded class reference is " + System.identityHashCode(reloadedClass)
            + " should be the same as the original one " + System.identityHashCode(originalClass))
        .isSameAs(originalClass);
    assertTrue(originalClass == loadedClass);
    assertTrue(originalClass == reloadedClass);

    assertEquals(reloadedClass.getProperty("modification").getType(), PropertyType.STRING);
  }

//  @Test
//  public void testReadPropertiesFromStorage() {
//    Schema schema = db.getMetadata().getSchema();
//
//    SchemaClass classWithProperty = schema.createClass("propertizedClass");
//    classWithProperty.createProperty(db, "prop1", PropertyType.STRING);
//
//    // we need db reload to force reread schema from storage,
//    // I am working on a bug where in-memory schema is fine, but after reload class properties are empty
//    Storage storage = db.getStorage();
//    storage.reload(db);
//    db.getMetadata().reload();
//    SchemaInternal anotherSchema = db.getMetadata().getSchema();
//
//    SchemaClass coldClass = anotherSchema.getClass("propertizedClass");
//    assertNotNull(coldClass.getProperty("prop1"));
//  }

  @Test
  public void testReadPropertiesFromStorage2() {
    final YouTrackDB youTrackDb =
        new YouTrackDBImpl(
            DbTestBase.embeddedDBUrl(getClass()),
            YouTrackDBConfig.builder()
                .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 1)
                .addGlobalConfigurationParameter(GlobalConfiguration.CREATE_DEFAULT_USERS, false)
                .build());
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
    db2.execute("sql", "create class propertizedClass");
    db2.execute("sql", "create property propertizedClass.prop1 STRING");
    final SessionPool pool =
        youTrackDb.cachedPool("test", "admin", CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    var db = (DatabaseSessionInternal) pool.acquire();
    SchemaClass coldClass = session.getMetadata().getSchema().getClass("propertizedClass");
    assertEquals(coldClass.getProperty("prop1").getType(), PropertyType.STRING);

    youTrackDb.close();
  }
}
