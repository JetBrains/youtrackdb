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

package com.jetbrains.youtrack.db.internal.core;

import com.jetbrains.youtrack.db.api.config.ContextConfiguration;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.conflict.RecordConflictStrategy;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.CurrentStorageComponentsFactory;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.engine.Engine;
import com.jetbrains.youtrack.db.internal.core.engine.EngineAbstract;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.ReadRecordResult;
import com.jetbrains.youtrack.db.internal.core.storage.RecordMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.StorageCluster;
import com.jetbrains.youtrack.db.internal.core.storage.config.ClusterBasedStorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.storage.ridbag.BTreeCollectionManager;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 */
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
            DatabaseDocumentTx.closeAll();
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
        YouTrackDBInternal context) {
      return new Storage() {

        @Override
        public List<String> backup(
            DatabaseSessionInternal db, OutputStream out,
            Map<String, Object> options,
            Callable<Object> callable,
            CommandOutputListener iListener,
            int compressionLevel,
            int bufferSize) {
          return null;
        }

        @Override
        public void restore(
            InputStream in,
            Map<String, Object> options,
            Callable<Object> callable,
            CommandOutputListener iListener) {
        }

        @Override
        public String getClusterName(DatabaseSessionInternal database, int clusterId) {
          return null;
        }

        @Override
        public boolean setClusterAttribute(int id, StorageCluster.ATTRIBUTES attribute,
            Object value) {
          return false;
        }

        @Override
        public String getCreatedAtVersion() {
          return null;
        }

        @Override
        public void open(
            DatabaseSessionInternal remote, String iUserName, String iUserPassword,
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
        public void reload(DatabaseSessionInternal database) {
        }

        @Override
        public void delete() {
        }

        @Override
        public void close(DatabaseSessionInternal session) {
        }

        @Override
        public void close(DatabaseSessionInternal database, boolean iForce) {
        }

        @Override
        public boolean isClosed(DatabaseSessionInternal database) {
          return false;
        }

        @Override
        public @Nonnull ReadRecordResult readRecord(
            DatabaseSessionInternal session, RecordId iRid, boolean fetchPreviousRid,
            boolean fetchNextRid) {
          return null;
        }

        @Override
        public boolean recordExists(DatabaseSessionInternal session, RID rid) {
          return false;
        }

        @Override
        public RecordMetadata getRecordMetadata(DatabaseSessionInternal session, RID rid) {
          return null;
        }

        @Override
        public List<RecordOperation> commit(FrontendTransactionOptimistic iTx) {
          return null;
        }

        @Override
        public ClusterBasedStorageConfiguration getConfiguration() {
          return null;
        }

        @Override
        public int getClusters() {
          return 0;
        }

        @Override
        public Set<String> getClusterNames() {
          return null;
        }

        @Override
        public Collection<? extends StorageCluster> getClusterInstances() {
          return null;
        }

        @Override
        public int addCluster(DatabaseSessionInternal database, String iClusterName,
            Object... iParameters) {
          return 0;
        }

        @Override
        public int addCluster(DatabaseSessionInternal database, String iClusterName,
            int iRequestedId) {
          return 0;
        }

        @Override
        public boolean dropCluster(DatabaseSessionInternal session, String iClusterName) {
          return false;
        }

        @Override
        public boolean dropCluster(DatabaseSessionInternal database, int iId) {
          return false;
        }

        @Override
        public String getClusterNameById(int clusterId) {
          return null;
        }

        @Override
        public long getClusterRecordsSizeById(int clusterId) {
          return 0;
        }

        @Override
        public long getClusterRecordsSizeByName(String clusterName) {
          return 0;
        }

        @Override
        public String getClusterRecordConflictStrategy(int clusterId) {
          return null;
        }

        @Override
        public boolean isSystemCluster(int clusterId) {
          return false;
        }

        @Override
        public long count(DatabaseSessionInternal session, int iClusterId) {
          return 0;
        }

        @Override
        public long count(DatabaseSessionInternal session, int iClusterId,
            boolean countTombstones) {
          return 0;
        }

        @Override
        public long count(DatabaseSessionInternal session, int[] iClusterIds) {
          return 0;
        }

        @Override
        public long count(DatabaseSessionInternal session, int[] iClusterIds,
            boolean countTombstones) {
          return 0;
        }

        @Override
        public long getSize(DatabaseSessionInternal session) {
          return 0;
        }

        @Override
        public long countRecords(DatabaseSessionInternal session) {
          return 0;
        }

        @Override
        public int getClusterIdByName(String iClusterName) {
          return 0;
        }

        @Override
        public String getPhysicalClusterNameById(int iClusterId) {
          return null;
        }

        @Override
        public boolean checkForRecordValidity(PhysicalPosition ppos) {
          return false;
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
        public Object command(DatabaseSessionInternal db, CommandRequestText iCommand) {
          return null;
        }

        @Override
        public PhysicalPosition[] higherPhysicalPositions(
            DatabaseSessionInternal session, int clusterId, PhysicalPosition physicalPosition,
            int limit) {
          return new PhysicalPosition[0];
        }

        @Override
        public PhysicalPosition[] lowerPhysicalPositions(
            DatabaseSessionInternal session, int clusterId, PhysicalPosition physicalPosition,
            int limit) {
          return new PhysicalPosition[0];
        }

        @Override
        public PhysicalPosition[] ceilingPhysicalPositions(
            DatabaseSessionInternal session, int clusterId, PhysicalPosition physicalPosition,
            int limit) {
          return new PhysicalPosition[0];
        }

        @Override
        public PhysicalPosition[] floorPhysicalPositions(
            DatabaseSessionInternal session, int clusterId, PhysicalPosition physicalPosition,
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
        public boolean isAssigningClusterIds() {
          return false;
        }

        @Override
        public BTreeCollectionManager getSBtreeCollectionManager() {
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
        public String incrementalBackup(DatabaseSessionInternal session, String backupDirectory,
            CallableFunction<Void, Void> started) {
          return null;
        }

        @Override
        public void fullIncrementalBackup(final OutputStream stream)
            throws UnsupportedOperationException {
        }

        @Override
        public void restoreFromIncrementalBackup(DatabaseSessionInternal session,
            String filePath) {
        }

        @Override
        public void restoreFullIncrementalBackup(DatabaseSessionInternal session,
            final InputStream stream)
            throws UnsupportedOperationException {
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
        public void setClusterSelection(String clusterSelection) {
        }

        @Override
        public void setMinimumClusters(int minimumClusters) {
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
        public int[] getClustersIds(Set<String> filterClusters) {
          return null;
        }

        @Override
        public YouTrackDBInternal getContext() {
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
        YouTrackDBInternal context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getNameFromPath(String dbPath) {
      throw new UnsupportedOperationException();
    }
  }
}
