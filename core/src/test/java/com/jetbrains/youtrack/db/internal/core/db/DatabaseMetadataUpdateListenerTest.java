package com.jetbrains.youtrack.db.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.common.BasicDatabaseSession;
import com.jetbrains.youtrack.db.api.common.BasicYouTrackDB;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaShared;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.DBSequence;
import java.util.Locale;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DatabaseMetadataUpdateListenerTest {
  private BasicYouTrackDB youTrackDB;
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
          public void onSchemaUpdate(DatabaseSessionInternal session, String databaseName,
              SchemaShared schema) {
            schemaCount++;
            assertNotNull(schema);
          }

          @Override
          public void onIndexManagerUpdate(DatabaseSessionInternal session, String databaseName,
              IndexManagerAbstract indexManager) {
            indexManagerUpdateCount++;
            assertNotNull(indexManager);
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
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
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
