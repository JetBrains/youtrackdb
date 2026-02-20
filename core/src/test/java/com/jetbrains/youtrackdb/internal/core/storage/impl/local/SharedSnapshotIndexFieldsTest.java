package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.collection.SnapshotKey;
import com.jetbrains.youtrackdb.internal.core.storage.collection.VisibilityKey;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the shared snapshot/visibility index fields and accessors added to
 * {@link AbstractStorage} as part of YTDB-510.
 */
public class SharedSnapshotIndexFieldsTest {

  private YouTrackDBImpl youTrackDB;
  private AbstractStorage storage;

  @Before
  public void setUp() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(
        "test", DatabaseType.MEMORY, getClass());
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) session.getStorage();
    session.close();
  }

  @After
  public void tearDown() {
    if (youTrackDB != null) {
      youTrackDB.close();
    }
  }

  @Test
  public void testSharedSnapshotIndexInitialized() {
    assertThat(storage.getSharedSnapshotIndex()).isNotNull();
    assertThat(storage.getSharedSnapshotIndex()).isEmpty();
  }

  @Test
  public void testVisibilityIndexInitialized() {
    assertThat(storage.getVisibilityIndex()).isNotNull();
    assertThat(storage.getVisibilityIndex()).isEmpty();
  }

  @Test
  public void testGetSharedSnapshotIndexReturnsStableInstance() {
    ConcurrentSkipListMap<SnapshotKey, PositionEntry> first =
        storage.getSharedSnapshotIndex();
    ConcurrentSkipListMap<SnapshotKey, PositionEntry> second =
        storage.getSharedSnapshotIndex();
    assertThat(first).isSameAs(second);
  }

  @Test
  public void testGetVisibilityIndexReturnsStableInstance() {
    ConcurrentSkipListMap<VisibilityKey, SnapshotKey> first =
        storage.getVisibilityIndex();
    ConcurrentSkipListMap<VisibilityKey, SnapshotKey> second =
        storage.getVisibilityIndex();
    assertThat(first).isSameAs(second);
  }

  @Test
  public void testSharedSnapshotIndexPutAndGet() {
    var key = new SnapshotKey(1, 100L, 10L);
    var entry = new PositionEntry(5L, 3, 10L);
    storage.getSharedSnapshotIndex().put(key, entry);

    assertThat(storage.getSharedSnapshotIndex()).containsKey(key);
    assertThat(storage.getSharedSnapshotIndex().get(key)).isEqualTo(entry);
  }

  @Test
  public void testVisibilityIndexPutAndGet() {
    var vKey = new VisibilityKey(10L, 1, 100L);
    var sKey = new SnapshotKey(1, 100L, 5L);
    storage.getVisibilityIndex().put(vKey, sKey);

    assertThat(storage.getVisibilityIndex()).containsKey(vKey);
    assertThat(storage.getVisibilityIndex().get(vKey)).isEqualTo(sKey);
  }

  @Test
  public void testSharedSnapshotIndexReturnsConcurrentSkipListMap() {
    assertThat(storage.getSharedSnapshotIndex())
        .isInstanceOf(ConcurrentSkipListMap.class);
  }

  @Test
  public void testVisibilityIndexReturnsConcurrentSkipListMap() {
    assertThat(storage.getVisibilityIndex())
        .isInstanceOf(ConcurrentSkipListMap.class);
  }

  @Test
  public void testCleanupThresholdConfigurationExists() {
    GlobalConfiguration config =
        GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD;

    assertThat(config).isNotNull();
    assertThat(config.getKey())
        .isEqualTo("youtrackdb.storage.snapshotIndex.cleanupThreshold");
    assertThat(config.getDefValue()).isEqualTo(10_000);
    assertThat(config.getType()).isEqualTo(Integer.class);
  }

  @Test
  public void testCleanupThresholdDefaultValue() {
    int threshold =
        GlobalConfiguration.STORAGE_SNAPSHOT_INDEX_CLEANUP_THRESHOLD
            .getValueAsInteger();
    assertThat(threshold).isEqualTo(10_000);
  }
}
