package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.IndexEngineTestSupport;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Real-storage close schedules with persisted record and index-count assertions after reopen. */
@Category(SequentialTest.class)
public class AbstractStorageForcedCloseTest {

  private final String name = "forcedClose_" + UUID.randomUUID();
  private YouTrackDBImpl manager;
  private DatabaseSessionEmbedded db;
  private AbstractStorage storage;
  private IndexAbstract index;
  private int histogramMinSize;

  @Before
  public void setUp() {
    histogramMinSize = GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE.getValueAsInteger();
    GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE.setValue(1);
    manager = DbTestBase.createYTDBManagerAndDb(name, DatabaseType.DISK, getClass());
    db = manager.open(name, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) db.getStorage();
    db.createClassIfNotExist("CloseRecord");
    var cls = db.getClass("CloseRecord");
    cls.createProperty("value", PropertyType.STRING);
    cls.createIndex("CloseRecord.value", SchemaClass.INDEX_TYPE.UNIQUE, "value");
    db.begin();
    db.newEntity("CloseRecord").setProperty("value", "retained");
    db.commit();
    index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex("CloseRecord.value");
  }

  @After
  public void tearDown() {
    try {
      try {
        if (db != null) {
          db.activateOnCurrentThread();
          db.close();
        }
      } finally {
        if (manager != null) {
          manager.close();
        }
      }
    } finally {
      GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE.setValue(histogramMinSize);
    }
  }

  /** A closed storage retains its published limit, and reopening reloads the persisted value. */
  @Test
  public void recordedKeyLimitSurvivesCloseAndRefreshesOnReopen() throws Exception {
    int recorded = storage.getRecordedMaxIndexKeySize();
    assertThat(recorded).isEqualTo(
        GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger());
    var configuration = (CollectionBasedStorageConfiguration) storage.configuration;
    storage.getAtomicOperationsManager().executeInsideAtomicOperation(
        op -> configuration.setMaxKeySize(op, -1));
    storage.close(null, true);
    assertThat(storage.getRecordedMaxIndexKeySize()).isEqualTo(recorded);
    reopen();
    assertThat(storage.getRecordedMaxIndexKeySize()).isEqualTo(-1);
    assertRecords(1);
  }

  /** Each incompatible caller context rejects before lock acquisition and permits a later write. */
  @Test(timeout = 30_000)
  public void protectedForcedCloseRejectsReadWriteAndCommitWindowWithoutPoisoning()
      throws Exception {
    db.activateOnCurrentThread();
    var original = storage.stateLock;
    var observed = spy(original);
    doAnswer(call -> original.isWriteLockedByCurrentThread())
        .when(observed).isWriteLockedByCurrentThread();
    // Fail rather than hang if the precheck is missing. Ownership still comes from real locks.
    doThrow(new AssertionError("forced close reached non-reentrant write acquisition"))
        .when(observed).writeLock();
    setField("stateLock", observed);
    try {
      original.readLock().lock();
      try {
        assertContextRejection();
      } finally {
        original.readLock().unlock();
      }
      original.writeLock().lock();
      try {
        assertContextRejection();
      } finally {
        original.writeLock().unlock();
      }
      storage.enterCommitWindow();
      try {
        assertContextRejection();
      } finally {
        storage.exitCommitWindow();
      }
    } finally {
      setField("stateLock", original);
    }
    db.begin();
    db.newEntity("CloseRecord").setProperty("value", "afterRejection");
    db.commit();
    closeAndReopen();
    assertRecords(2);
  }

  private void assertContextRejection() {
    assertThatThrownBy(() -> storage.close(null, true))
        .isExactlyInstanceOf(StorageStateContextException.class)
        .hasMessageContaining("state lock").hasMessageContaining("commit window");
  }

  /** Close reaches reader drain while a real commit is paused, then persists its new record. */
  @Test(timeout = 30_000)
  public void forcedCloseWaitsForCommitAndPersistsItsRecord() throws Exception {
    var admitted = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    storage.setBeforeCommitApplyActionForTesting(() -> {
      assertThat(storage.stateLock.isReadLockedByCurrentThread()).isTrue();
      admitted.countDown();
      await(resume);
    });
    try {
      var writer = workers.submit(() -> {
        db.activateOnCurrentThread();
        db.begin();
        db.newEntity("CloseRecord").setProperty("value", "committed");
        db.commit();
      });
      assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
      var close = workers.submit(() -> storage.close(null, true));
      awaitReaderDrain(close);
      resume.countDown();
      writer.get(10, TimeUnit.SECONDS);
      close.get(10, TimeUnit.SECONDS);
      reopen();
      assertRecords(2);
    } finally {
      resume.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** The complete histogram operation holds read protection and its recalibration survives close. */
  @Test(timeout = 30_000)
  public void forcedCloseWaitsForHistogramAndPersistsItsCounts() throws Exception {
    var initialEngine = observeEngine();
    // Seed durable drift, not just heap drift. Without histogram recalibration, reopen loads 11.
    storage.stateLock.readLock().lock();
    try {
      storage.makeStorageDirty();
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> initialEngine.persistCountDelta(op, 10, 0));
      initialEngine.addToApproximateEntriesCount(10);
    } finally {
      storage.stateLock.readLock().unlock();
    }
    closeAndReopen();
    var engine = observeEngine();
    storage.getAtomicOperationsManager().executeInsideAtomicOperation(
        op -> assertThat(engine.getTotalCount(op)).isEqualTo(11));
    var admitted = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    doAnswer(call -> {
      assertThat(storage.stateLock.isReadLockedByCurrentThread())
          .as("histogram must hold state protection during atomic apply").isTrue();
      admitted.countDown();
      await(resume);
      return call.callRealMethod();
    }).when(engine).buildInitialHistogram(any());
    var workers = Executors.newFixedThreadPool(2);
    try {
      var histogram = workers.submit(this::buildHistogram);
      assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
      var close = workers.submit(() -> storage.close(null, true));
      awaitReaderDrain(close);
      resume.countDown();
      histogram.get(10, TimeUnit.SECONDS);
      close.get(10, TimeUnit.SECONDS);
      reopen();
      assertRecords(1);
      var reloaded = (BTreeSingleValueIndexEngine) storage.getIndexEngine(
          IndexEngineTestSupport.externalIdentifier(index));
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        assertThat(reloaded.getTotalCount(op)).isEqualTo(1);
        assertThat(reloaded.getNullCount(op)).isZero();
      });
    } finally {
      resume.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** A histogram callback marks a clean startup marker dirty before atomic admission. */
  @Test
  public void histogramAfterCleanMarkerMarksDirtyBeforeAtomicAdmission() throws Exception {
    storage.synch();
    assertThat(storage.isDirty()).isFalse();
    var original = storage.getAtomicOperationsManager();
    var operations = spy(original);
    setField("atomicOperationsManager", operations);
    var admissions = new AtomicInteger();
    doAnswer(call -> {
      assertThat(storage.isDirty()).as("dirty marking must precede atomic admission").isTrue();
      admissions.incrementAndGet();
      return call.callRealMethod();
    }).when(operations).startAtomicOperation();
    try {
      buildHistogram();
      assertThat(admissions.get()).isEqualTo(1);
      assertThat(storage.isDirty()).isTrue();
    } finally {
      // Remove the test observer before shutdown starts its own atomic operations.
      setField("atomicOperationsManager", original);
    }
  }

  /** A late histogram waits through post-checkpoint close and fails openness before admission. */
  @Test(timeout = 30_000)
  public void lateHistogramFailsOpennessBeforeAtomicAdmission() throws Exception {
    var closing = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    var lateThread = new AtomicReference<Thread>();
    var admissions = new AtomicInteger();
    var original = storage.getAtomicOperationsManager();
    var operations = spy(original);
    setField("atomicOperationsManager", operations);
    doAnswer(call -> {
      if (Thread.currentThread().getName().equals("late-histogram")) {
        admissions.incrementAndGet();
      }
      return call.callRealMethod();
    }).when(operations).startAtomicOperation();
    storage.setAfterShutdownRemarkActionForTesting(() -> {
      closing.countDown();
      await(resume);
    });
    var workers = Executors.newFixedThreadPool(2);
    try {
      var close = workers.submit(() -> storage.close(null, true));
      assertThat(closing.await(10, TimeUnit.SECONDS)).isTrue();
      var late = workers.submit(() -> {
        Thread.currentThread().setName("late-histogram");
        lateThread.set(Thread.currentThread());
        started.countDown();
        assertThatThrownBy(this::buildHistogram).hasMessageContaining("not opened");
      });
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      awaitReadLockSpin(lateThread.get(), late);
      assertThat(close.isDone()).isFalse();
      assertThat(admissions.get()).isZero();
      resume.countDown();
      close.get(10, TimeUnit.SECONDS);
      late.get(10, TimeUnit.SECONDS);
      assertThat(admissions.get()).isZero();
      setField("atomicOperationsManager", original);
      reopen();
      assertRecords(1);
    } finally {
      resume.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      // A reopened storage owns a fresh manager. Do not replace it with the closed instance.
    }
  }

  private BTreeSingleValueIndexEngine observeEngine() throws Exception {
    var engine = (BTreeSingleValueIndexEngine) storage.getIndexEngine(
        IndexEngineTestSupport.externalIdentifier(index));
    var observer = spy(engine);
    var field = AbstractStorage.class.getDeclaredField("indexEngines");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var engines = (List<BaseIndexEngine>) field.get(storage);
    engines.set(engines.indexOf(engine), observer);
    return observer;
  }

  private void buildHistogram() {
    // Invoke only the rebuild tail so fillIndex writes cannot hide its own admission or marker bug.
    try {
      var method = IndexAbstract.class.getDeclaredMethod("buildHistogramAfterFill");
      method.setAccessible(true);
      method.invoke(index);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new AssertionError(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private void awaitReaderDrain(Future<?> close) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!storage.stateLock.isWriteLocked() && !close.isDone()
        && System.nanoTime() < deadline) {
      Thread.yield();
    }
    if (close.isDone()) {
      close.get(10, TimeUnit.SECONDS);
    }
    assertThat(storage.stateLock.isWriteLocked()).isTrue();
    assertThat(close.isDone()).isFalse();
  }

  private void awaitReadLockSpin(Thread thread, Future<?> late) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!late.isDone() && System.nanoTime() < deadline) {
      // ScalableRWLock readers busy-yield, so WAITING/BLOCKED cannot identify their wait.
      // Seeing yield inside sharedLock proves acquisition reached the writer-held spin.
      boolean inSharedLock = false;
      boolean yielding = false;
      for (var frame : thread.getStackTrace()) {
        inSharedLock |= frame.getClassName().equals(ScalableRWLock.class.getName())
            && frame.getMethodName().equals("sharedLock");
        yielding |= frame.getClassName().equals(Thread.class.getName())
            && frame.getMethodName().startsWith("yield");
      }
      if (inSharedLock && yielding) {
        assertThat(storage.stateLock.isWriteLocked()).isTrue();
        return;
      }
      Thread.yield();
    }
    if (late.isDone()) {
      late.get(10, TimeUnit.SECONDS);
    }
    throw new AssertionError("late histogram did not reach the writer-held read-lock spin");
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private void closeAndReopen() {
    storage.close(null, true);
    reopen();
  }

  private void reopen() {
    db.activateOnCurrentThread();
    db.close();
    // Discard the manager's closed shared context before creating a fresh authenticated session.
    manager.internal.forceDatabaseClose(name);
    db = manager.open(name, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) db.getStorage();
    index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex("CloseRecord.value");
  }

  private void assertRecords(long expected) {
    db.begin();
    try (var result = db.query("select from CloseRecord")) {
      assertThat(result.stream().count()).isEqualTo(expected);
    }
    db.commit();
  }

  private void setField(String name, Object value) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(storage, value);
  }
}
