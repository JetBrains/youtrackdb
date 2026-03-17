package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper.RIDMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class EntityHelperTest {

  private static final String dbName = EntityHelperTest.class.getSimpleName();
  private static final String defaultDbAdminCredentials = "admin";

  @Test
  public void shouldComparePasswordHash() {
    DatabaseSessionEmbedded session = null;
    YouTrackDBImpl ytdb = null;
    try {
      ytdb = (YouTrackDBImpl) CreateDatabaseUtil.createDatabase(dbName + "1",
          DbTestBase.embeddedDBUrl(EntityHelperTest.class) + "temp1",
          CreateDatabaseUtil.TYPE_MEMORY);
      session = (DatabaseSessionEmbedded) ytdb.open(dbName + "1", defaultDbAdminCredentials,
          CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      var clazz1 = session.getMetadata().getSchema().createClass("HashHolder");
      clazz1.createProperty("hash", PropertyType.BINARY);

      session.begin();
      var entity1 = (EntityImpl) session.newEntity(clazz1);
      var bytes = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
      entity1.setProperty("hash", bytes);

      var entity2 = (EntityImpl) session.newEntity(clazz1);
      entity2.setProperty("hash", bytes);

      var modifiedHashEntity = (EntityImpl) session.newEntity(clazz1);
      var modifiedBytes = new byte[bytes.length];
      System.arraycopy(bytes, 0, modifiedBytes, 0, bytes.length);
      modifiedBytes[0] = modifiedBytes[0] == 127 ? 0 : (byte) (modifiedBytes[0] + 1);
      modifiedHashEntity.setProperty("hash", modifiedBytes);
      var mapper = mock(RIDMapper.class);
      assertTrue(EntityHelper.hasSameContentOf(entity1, session, entity2, session, mapper, false));
      assertFalse(
          EntityHelper.hasSameContentOf(entity1, session, modifiedHashEntity, session, mapper,
              false));

      session.commit();
    } finally {
      if (session != null) {
        session.close();
      }
      if (ytdb != null) {
        ytdb.drop(dbName + "1");
        ytdb.close();
      }
    }
  }

  @Test
  public void shouldNotComparePasswordHashIfConfigured() {
    DatabaseSessionEmbedded session = null;
    YouTrackDBImpl ytdb = null;
    try {
      ytdb = (YouTrackDBImpl) CreateDatabaseUtil.createDatabase(dbName + "1",
          DbTestBase.embeddedDBUrl(EntityHelperTest.class) + "temp1",
          CreateDatabaseUtil.TYPE_MEMORY);
      session = (DatabaseSessionEmbedded) ytdb.open(dbName + "1", defaultDbAdminCredentials,
          CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
      var clazz1 = session.getMetadata().getSchema().createClass("HashHolder");
      clazz1.createProperty("hash", PropertyType.BINARY);

      session.begin();
      var entity1 = (EntityImpl) session.newEntity(clazz1);
      var bytes = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
      entity1.setProperty("hash", bytes);

      var entity2 = (EntityImpl) session.newEntity(clazz1);
      entity2.setProperty("hash", bytes);

      var modifiedHashEntity = (EntityImpl) session.newEntity(clazz1);
      var modifiedBytes = new byte[bytes.length];
      System.arraycopy(bytes, 0, modifiedBytes, 0, bytes.length);
      modifiedBytes[0] = modifiedBytes[0] == 127 ? 0 : (byte) (modifiedBytes[0] + 1);
      modifiedHashEntity.setProperty("hash", modifiedBytes);
      var mapper = mock(RIDMapper.class);
      assertTrue(EntityHelper.hasSameContentOf(entity1, session, entity2, session, mapper, false));
      assertTrue(
          EntityHelper.hasSameContentOf(entity1, session, modifiedHashEntity, session, mapper,
              false, Set.of("hash")));

      session.commit();
    } finally {
      if (session != null) {
        session.close();
      }
      if (ytdb != null) {
        ytdb.drop(dbName + "1");
        ytdb.close();
      }
    }
  }
}