package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.ProxedResource;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.function.IntFunction;
import org.junit.Test;

/**
 * @since 2/4/25
 */
// todo remove this test, it only will work while migrating to new split schema
public class SchemaEmbeddedTest extends DbTestBase {

  @Test
  public void testFromStreamSchemaContainsAllClasses()
      throws IllegalAccessException, NoSuchFieldException {
    try (YouTrackDB youTrackDB = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      String dbName = SchemaEmbeddedTest.class.getSimpleName();
      youTrackDB.execute(
          "create database "
              + dbName
              + " memory users (admin identified by 'admin' role admin)");
      var session = (DatabaseSessionInternal) youTrackDB.open(dbName, "admin", "admin");
      session.getSchema().createClass("logart");

      session.begin();
      SchemaProxy schemaProxy = (SchemaProxy) session.getSchema();
      MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ProxedResource.class, MethodHandles.lookup());
      VarHandle varHandle = lookup.findVarHandle(ProxedResource.class, "delegate", Object.class);

      SchemaEmbedded schema = (SchemaEmbedded) varHandle.get(schemaProxy);
      EntityImpl schemaEntity = session.executeReadRecord(
          schema.getIdentity());
      schema.fromStream(session, schemaEntity);
      session.commit();
      String[] classNames = schema.classes.keySet().stream().sorted().toArray(String[]::new);
      String[] classRefsNames = schema.classesRefs.keySet().stream().sorted()
          .toArray(String[]::new);
      assertArrayEquals(classNames, classRefsNames);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T[] extractKeys(DatabaseSessionInternal session, EntityImpl schemaEntity,
      String key,
      IntFunction<T[]> arrayGenerator) {
    Object property = schemaEntity.getProperty(key);
    if (property == null) {
      return arrayGenerator.apply(0);
    }
    return (T[]) ((HashSet) property).stream()
        .map(a -> getName(session, a))
        .sorted()
        .toArray(arrayGenerator);
  }

  private static String getName(DatabaseSessionInternal session, Object a) {
    EntityImpl entity = null;
    if (a instanceof EntityImpl e) {
      entity = e;
    }
    if (a instanceof RecordId rId) {
      entity = session.executeReadRecord(rId);
    }
    if (entity != null) {
      return entity.getProperty("name");
    }
    throw new IllegalArgumentException("Name could not be extracted from type: " + a.getClass());
  }
}