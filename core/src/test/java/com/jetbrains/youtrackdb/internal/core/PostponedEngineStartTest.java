/*
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 */

package com.jetbrains.youtrackdb.internal.core;

import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.engine.Engine;
import com.jetbrains.youtrackdb.internal.core.engine.EngineAbstract;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrackdb.internal.core.storage.RawBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection.ATTRIBUTES;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.AbsoluteChange;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManager;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.Collection;
import java.util.Set;
import java.util.TimeZone;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class PostponedEngineStartTest {

  private static YouTrackDBEnginesManager YOUTRACKDB;

  private static Engine ENGINE1;
  private static Engine ENGINE2;
  private static Engine FAULTY_ENGINE;

  @BeforeClass
  public static void before() {
    YOUTRACKDB =
        new YouTrackDBEnginesManager(false) {
          @Override
          public YouTrackDBEnginesManager startup() {
            YOUTRACKDB.registerEngine(ENGINE1 = new NamedEngine("engine1"));
            YOUTRACKDB.registerEngine(ENGINE2 = new NamedEngine("engine2"));
            YOUTRACKDB.registerEngine(FAULTY_ENGINE = new FaultyEngine());
            return this;
          }

          @Override
          public YouTrackDBEnginesManager shutdown() {
            return this;
          }
        };

    YOUTRACKDB.startup();
  }

  @Test
  public void test() {
    // XXX: There is a known problem in TestNG runner with hardly controllable test methods
    // interleaving from different
    // test classes. This test case touches internals of YouTrackDB runtime, interleaving with foreign
    // methods is not acceptable
    // here. So I just invoke "test" methods manually from a single test method.
    //
    // BTW, TestNG author says that is not a problem, we just need to split *ALL* our test classes
    // into groups and
    // make groups depend on each other in right order. I see many problems here: (a) we have to to
    // split into groups,
    // (b) we have to maintain all that zoo and (c) we lose the ability to run each test case
    // individually since
    // group dependency must be run before.

    testEngineShouldNotStartAtRuntimeStart();
    testGetEngineIfRunningShouldReturnNullEngineIfNotRunning();
    testGetRunningEngineShouldStartEngine();
    testEngineRestart();
    testStoppedEngineShouldStartAndCreateStorage();
    testGetRunningEngineShouldThrowIfEngineIsUnknown();
    testGetRunningEngineShouldThrowIfEngineIsUnableToStart();
  }

  // @Test
  public void testEngineShouldNotStartAtRuntimeStart() {
    final var engine = YOUTRACKDB.getEngine(ENGINE1.getName());
    Assert.assertFalse(engine.isRunning());
  }

  // @Test(dependsOnMethods = "testEngineShouldNotStartAtRuntimeStart")
  public void testGetEngineIfRunningShouldReturnNullEngineIfNotRunning() {
    final var engine = YOUTRACKDB.getEngineIfRunning(ENGINE1.getName());
    Assert.assertNull(engine);
  }

  // @Test(dependsOnMethods = "testGetEngineIfRunningShouldReturnNullEngineIfNotRunning")
  public void testGetRunningEngineShouldStartEngine() {
    final var engine = YOUTRACKDB.getRunningEngine(ENGINE1.getName());
    Assert.assertNotNull(engine);
    Assert.assertTrue(engine.isRunning());
  }

  // @Test(dependsOnMethods = "testGetRunningEngineShouldStartEngine")
  public void testEngineRestart() {
    var engine = YOUTRACKDB.getRunningEngine(ENGINE1.getName());
    engine.shutdown();
    Assert.assertFalse(engine.isRunning());

    engine = YOUTRACKDB.getEngineIfRunning(ENGINE1.getName());
    Assert.assertNull(engine);

    engine = YOUTRACKDB.getEngine(ENGINE1.getName());
    Assert.assertFalse(engine.isRunning());

    engine = YOUTRACKDB.getRunningEngine(ENGINE1.getName());
    Assert.assertTrue(engine.isRunning());
  }

  // @Test
  public void testStoppedEngineShouldStartAndCreateStorage() {
    var engine = YOUTRACKDB.getEngineIfRunning(ENGINE2.getName());
    Assert.assertNull(engine);

    final var storage =
        ENGINE2.createStorage(
            ENGINE2.getName() + ":storage",
            125 * 1024 * 1024,
            25 * 1024 * 1024,
            Integer.MAX_VALUE,
            null);

    Assert.assertNotNull(storage);

    engine = YOUTRACKDB.getRunningEngine(ENGINE2.getName());
    Assert.assertTrue(engine.isRunning());
  }

  //  @Test(expected = IllegalStateException.class)
  public void testGetRunningEngineShouldThrowIfEngineIsUnknown() {
    try {
      YOUTRACKDB.getRunningEngine("unknown engine");
      Assert.fail();
    } catch (Exception e) {
      // exception expected
    }
  }

  // @Test(expected = IllegalStateException.class)
  public void testGetRunningEngineShouldThrowIfEngineIsUnableToStart() {
    var engine = YOUTRACKDB.getEngine(FAULTY_ENGINE.getName());
    Assert.assertNotNull(engine);
    try {

      YOUTRACKDB.getRunningEngine(FAULTY_ENGINE.getName());

      engine = YOUTRACKDB.getEngine(FAULTY_ENGINE.getName());
      Assert.assertNull(engine);
      Assert.fail();
    } catch (Exception e) {
      // exception expected
    }
  }

  private static class NamedEngine extends EngineAbstract {

    private final String name;

    public NamedEngine(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Storage createStorage(
        String iURL,
        long maxWalSegSize,
        long doubleWriteLogMaxSegSize,
        int storageId,
        YouTrackDBInternalEmbedded context) {
      return new Storage() {


        @Override
        public String getCollectionName(DatabaseSessionEmbedded database, int collectionId) {
          return null;
        }

        @Override
        public void setCollectionAttribute(int id, ATTRIBUTES attribute,
            Object value) {
        }

        @Override
        public String getCreatedAtVersion() {
          return null;
        }

        @Override
        public void open(
            DatabaseSessionEmbedded remote, String iUserName, String iUserPassword,
            ContextConfiguration contextConfiguration) {
        }

        @Override
        public void create(ContextConfiguration contextConfiguration) {
        }

        @Override
        public boolean exists() {
          return false;
        }

        @Override
        public void reload(DatabaseSessionEmbedded database) {
        }

        @Override
        public void delete() {
        }

        @Override
        public void close(DatabaseSessionEmbedded session) {
        }

        @Override
        public void close(DatabaseSessionEmbedded database, boolean iForce) {
        }

        @Override
        public boolean isClosed(DatabaseSessionEmbedded database) {
          return false;
        }

        @Override
        public @Nonnull RawBuffer readRecord(RecordIdInternal iRid) {
          return null;
        }

        @Override
        public boolean recordExists(DatabaseSessionEmbedded session, RID rid) {
          return false;
        }

        @Override
        public RecordMetadata getRecordMetadata(DatabaseSessionEmbedded session, RID rid) {
          return null;
        }

        @Override
        public void commit(FrontendTransactionImpl iTx) {
        }

        @Override
        public CollectionBasedStorageConfiguration getConfiguration() {
          return null;
        }

        @Override
        public int getCollections() {
          return 0;
        }

        @Override
        public Set<String> getCollectionNames() {
          return null;
        }

        @Override
        public Collection<? extends StorageCollection> getCollectionInstances() {
          return null;
        }

        @Override
        public int addCollection(DatabaseSessionEmbedded database, String iCollectionName,
            Object... iParameters) {
          return 0;
        }

        @Override
        public int getAbsoluteLinkBagCounter(RID ownerId, String fieldName, RID key) {
          return 0;
        }

        @Override
        public int addCollection(DatabaseSessionEmbedded database, String iCollectionName,
            int iRequestedId) {
          return 0;
        }

        @Override
        public boolean dropCollection(DatabaseSessionEmbedded session, String iCollectionName) {
          return false;
        }

        @Override
        public boolean dropCollection(DatabaseSessionEmbedded database, int iId) {
          return false;
        }

        @Override
        public String getCollectionNameById(int collectionId) {
          return null;
        }

        @Override
        public long getCollectionRecordsSizeById(int collectionId) {
          return 0;
        }

        @Override
        public long getCollectionRecordsSizeByName(String collectionName) {
          return 0;
        }

        @Override
        public String getCollectionRecordConflictStrategy(int collectionId) {
          return null;
        }

        @Override
        public boolean isSystemCollection(int collectionId) {
          return false;
        }

        @Override
        public long count(DatabaseSessionEmbedded session, int iCollectionId) {
          return 0;
        }

        @Override
        public long count(DatabaseSessionEmbedded session, int iCollectionId,
            boolean countTombstones) {
          return 0;
        }

        @Override
        public long count(DatabaseSessionEmbedded session, int[] iCollectionIds) {
          return 0;
        }

        @Override
        public long count(DatabaseSessionEmbedded session, int[] iCollectionIds,
            boolean countTombstones) {
          return 0;
        }

        @Override
        public long getSize(DatabaseSessionEmbedded session) {
          return 0;
        }

        @Override
        public AbsoluteChange getLinkBagCounter(DatabaseSessionEmbedded session,
            RecordIdInternal identity,
            String fieldName, RID rid) {
          return null;
        }

        @Override
        public long countRecords(DatabaseSessionEmbedded session) {
          return 0;
        }

        @Override
        public int getCollectionIdByName(String iCollectionName) {
          return 0;
        }

        @Override
        public String getPhysicalCollectionNameById(int iCollectionId) {
          return null;
        }

        @Override
        public String getName() {
          return null;
        }

        @Override
        public String getURL() {
          return null;
        }

        @Override
        public long getVersion() {
          return 0;
        }

        @Override
        public void synch() {
        }

        @Override
        public PhysicalPosition[] higherPhysicalPositions(
            DatabaseSessionEmbedded session, int collectionId, PhysicalPosition physicalPosition,
            int limit) {
          return new PhysicalPosition[0];
        }

        @Override
        public PhysicalPosition[] lowerPhysicalPositions(
            DatabaseSessionEmbedded session, int collectionId, PhysicalPosition physicalPosition,
            int limit) {
          return new PhysicalPosition[0];
        }

        @Override
        public PhysicalPosition[] ceilingPhysicalPositions(
            DatabaseSessionEmbedded session, int collectionId, PhysicalPosition physicalPosition,
            int limit) {
          return new PhysicalPosition[0];
        }

        @Override
        public PhysicalPosition[] floorPhysicalPositions(
            DatabaseSessionEmbedded session, int collectionId, PhysicalPosition physicalPosition,
            int limit) {
          return new PhysicalPosition[0];
        }

        @Override
        public STATUS getStatus() {
          return null;
        }

        @Override
        public String getType() {
          return null;
        }

        @Override
        public Storage getUnderlying() {
          return null;
        }

        @Override
        public boolean isRemote() {
          return false;
        }

        @Override
        public boolean isAssigningCollectionIds() {
          return false;
        }

        @Override
        public LinkCollectionsBTreeManager getLinkCollectionsBtreeCollectionManager() {
          return null;
        }

        @Override
        public CurrentStorageComponentsFactory getComponentsFactory() {
          return null;
        }

        @Override
        public RecordConflictStrategy getRecordConflictStrategy() {
          return null;
        }

        @Override
        public void setConflictStrategy(RecordConflictStrategy iResolver) {
        }


        @Override
        public void shutdown() {
        }

        @Override
        public void setSchemaRecordId(String schemaRecordId) {
        }

        @Override
        public void setDateFormat(String dateFormat) {
        }

        @Override
        public void setTimeZone(TimeZone timeZoneValue) {
        }

        @Override
        public void setLocaleLanguage(String locale) {
        }

        @Override
        public void setCharset(String charset) {
        }

        @Override
        public void setIndexMgrRecordId(String indexMgrRecordId) {
        }

        @Override
        public void setDateTimeFormat(String dateTimeFormat) {
        }

        @Override
        public void setLocaleCountry(String localeCountry) {
        }

        @Override
        public void setCollectionSelection(String collectionSelection) {
        }

        @Override
        public void setMinimumCollections(int minimumCollections) {
        }

        @Override
        public void setValidation(boolean validation) {
        }

        @Override
        public void removeProperty(String property) {
        }

        @Override
        public void setProperty(String property, String value) {
        }

        @Override
        public void setRecordSerializer(String recordSerializer, int version) {
        }

        @Override
        public void clearProperties() {
        }

        @Override
        public int[] getCollectionsIds(Set<String> filterCollections) {
          return null;
        }

        @Override
        public YouTrackDBInternalEmbedded getContext() {
          return null;
        }
      };
    }

    @Override
    public String getNameFromPath(String dbPath) {
      return dbPath;
    }
  }

  private static class FaultyEngine extends EngineAbstract {

    @Override
    public String getName() {
      return FaultyEngine.class.getSimpleName();
    }

    @Override
    public void startup() {
      super.startup();
      throw new RuntimeException("oops");
    }

    @Override
    public Storage createStorage(
        String iURL,
        long maxWalSegSize,
        long doubleWriteLogMaxSegSize,
        int storageId,
        YouTrackDBInternalEmbedded context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getNameFromPath(String dbPath) {
      throw new UnsupportedOperationException();
    }
  }
}
