package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.DBSequence;
import java.util.Locale;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DatabaseMetadataUpdateListenerTest {

  private YouTrackDBAbstract<?, ?> youTrackDB;
  private DatabaseSessionInternal session;
  private int configCount;
  private int sequenceCount;
  private int schemaCount;
  private int indexManagerUpdateCount;
  private int functionCount;

  @Before
  public void before() {
    youTrackDB =
        CreateDatabaseUtil.createDatabase("test", DbTestBase.embeddedDBUrl(getClass()),
            CreateDatabaseUtil.TYPE_MEMORY);
    session = (DatabaseSessionInternal) youTrackDB.open("test", "admin",
        CreateDatabaseUtil.NEW_ADMIN_PASSWORD);
    configCount = 0;
    schemaCount = 0;
    sequenceCount = 0;
    indexManagerUpdateCount = 0;
    functionCount = 0;

    var listener =
        new MetadataUpdateListener() {

          @Override
          public void onSchemaUpdate(DatabaseSessionInternal session, String databaseName) {
            schemaCount++;
            assertNotNull(schema);
          }

          @Override
          public void onFunctionLibraryUpdate(DatabaseSessionInternal session, String database) {
            functionCount++;
          }

          @Override
          public void onSequenceLibraryUpdate(DatabaseSessionInternal session,
              String databaseName) {
            sequenceCount++;
          }

          @Override
          public void onStorageConfigurationUpdate(String databaseName,
              StorageConfiguration update) {
            configCount++;
            assertNotNull(update);
          }
        };
    session.getSharedContext().registerListener(listener);
  }

  @Test
  public void testSchemaUpdateListener() {
    session.createClass("test1");
    assertEquals(1, schemaCount);
  }

  @Test
  public void testSequenceUpdate() {
    try {
      session
          .getMetadata()
          .getSequenceLibrary()
          .createSequence("sequence1", DBSequence.SEQUENCE_TYPE.ORDERED, null);
    } catch (DatabaseException exc) {
      exc.printStackTrace();
      Assert.fail("Failed to create sequence");
    }
    assertEquals(1, sequenceCount);
  }


  @Test
  public void testIndexConfigurationUpdate() {
    session.set(BasicDatabaseSession.ATTRIBUTES.LOCALE_COUNTRY, Locale.GERMAN);
    assertEquals(1, configCount);
  }

  @Test
  public void testIndexUpdate() {
    session
        .createClass("Some")
        .createProperty("test", PropertyType.STRING)
        .createIndex(IndexType.NOT_UNIQUE);
    assertEquals(1, indexManagerUpdateCount);
  }

  @Test
  public void testFunctionUpdateListener() {
    session.getMetadata().getFunctionLibrary().createFunction("some");
    assertEquals(1, functionCount);
  }


  @After
  public void after() {
    session.close();
    youTrackDB.close();
  }
}
