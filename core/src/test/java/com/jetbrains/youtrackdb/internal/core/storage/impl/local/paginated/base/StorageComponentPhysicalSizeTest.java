package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the contract of {@link StorageComponent#physicalSize(AtomicOperation, long,
 * StorageComponent.PhysicalReadIntent)}:
 *
 * <ul>
 *   <li><b>Delegation.</b> The helper routes through {@link AtomicOperation#filledUpTo(long)}
 *       exactly once per call — confirmed via a Mockito mock for every
 *       {@link StorageComponent.PhysicalReadIntent} constant so a future refactor that drops or
 *       changes the route (e.g., bypassing the atomic-operation layer to call {@code
 *       WriteCache.getFilledUpTo} directly) breaks the test.</li>
 *   <li><b>AOBT placeholder side-effect preserved.</b> The first call for a given {@code fileId}
 *       on a real {@code AtomicOperationBinaryTracking} instance registers a {@code FileChanges}
 *       placeholder so subsequent in-transaction queries hit the existing-entry arm instead of
 *       re-allocating. The test reaches AOBT through {@link Class#forName(String)} because the
 *       implementation class is package-private; it then reads AOBT's private {@code
 *       fileChanges} map via reflection and asserts the placeholder lands. A direct call to a
 *       hypothetical {@code WriteCache.getFilledUpTo} would skip this side-effect, hence the
 *       explicit pin.</li>
 * </ul>
 */
public class StorageComponentPhysicalSizeTest {

  private static final String AOBT_CLASS_NAME =
      "com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations"
          + ".AtomicOperationBinaryTracking";

  private static final int STORAGE_ID = 1;
  private static final long FILE_ID = composeFileId(42L, STORAGE_ID);

  private AbstractStorage mockStorage;
  private ReadCache mockReadCache;
  private WriteCache mockWriteCache;
  private AtomicOperationsManager mockAtomicOpsMgr;
  private TestStorageComponent component;

  @Before
  public void setUp() {
    mockReadCache = mock(ReadCache.class);
    mockWriteCache = mock(WriteCache.class);
    mockStorage = mock(AbstractStorage.class);
    mockAtomicOpsMgr = mock(AtomicOperationsManager.class);
    when(mockStorage.getReadCache()).thenReturn(mockReadCache);
    when(mockStorage.getWriteCache()).thenReturn(mockWriteCache);
    when(mockStorage.getAtomicOperationsManager()).thenReturn(mockAtomicOpsMgr);

    component = new TestStorageComponent(mockStorage);
  }

  /**
   * Every {@link StorageComponent.PhysicalReadIntent} constant must route through {@link
   * AtomicOperation#filledUpTo(long)} exactly once and return the value verbatim. Iterating over
   * every enum constant also rules out an accidental {@code switch} or {@code if} branch on
   * {@code intent} that would short-circuit one constant — a refactor that adds such branching
   * is a contract violation against the "intent is unused at runtime" rule and is caught here.
   *
   * <p>{@link org.mockito.Mockito#verifyNoInteractions} on the {@code WriteCache} mock + {@link
   * org.mockito.Mockito#verifyNoMoreInteractions} on the {@code AtomicOperation} mock together
   * guard the gated contract: a future bypass that called {@code WriteCache.getFilledUpTo}
   * directly (or any other {@code op} method) alongside {@code filledUpTo} would silently pass
   * the count-only assertion above but trip these two.
   */
  @Test
  public void physicalSizeDelegatesToFilledUpToForEveryIntent() {
    for (StorageComponent.PhysicalReadIntent intent : StorageComponent.PhysicalReadIntent
        .values()) {
      AtomicOperation op = mock(AtomicOperation.class);
      when(op.filledUpTo(anyLong())).thenReturn(123L);

      long observed = component.callPhysicalSize(op, FILE_ID, intent);

      assertThat(observed).as("intent=%s", intent).isEqualTo(123L);
      verify(op, times(1)).filledUpTo(FILE_ID);
      verifyNoMoreInteractions(op);
      verifyNoInteractions(mockWriteCache);
    }
  }

  /**
   * The first {@code physicalSize} call for a given {@code fileId} on a real {@code
   * AtomicOperationBinaryTracking} must register a {@code FileChanges} placeholder on AOBT's
   * private {@code fileChanges} map. The pin guards the "preserves the AOBT placeholder
   * side-effect" contract on the helper's Javadoc — a refactor that bypasses {@code
   * AtomicOperation.filledUpTo} (e.g., directly calling {@code WriteCache.getFilledUpTo}) would
   * skip the placeholder and break in-transaction callers that depend on the existing-entry arm
   * firing on the second call. The test uses a real AOBT (not a Mockito mock) so the actual
   * three-arm body inside the AOBT {@code filledUpTo} method runs.
   */
  @Test
  public void physicalSizeRegistersAobtPlaceholderOnFirstCall() throws Exception {
    AtomicOperation op = newRealAobt();
    when(mockWriteCache.getFilledUpTo(FILE_ID)).thenReturn(0L);

    // Sanity precondition: no placeholder yet.
    assertThat(fileChangesOf(op).containsKey(FILE_ID))
        .as("placeholder must not exist before the first physicalSize call")
        .isFalse();

    component.callPhysicalSize(
        op, FILE_ID, StorageComponent.PhysicalReadIntent.EP_LESS_PURE_SIZING);

    assertThat(fileChangesOf(op).containsKey(FILE_ID))
        .as("AOBT must register a FileChanges placeholder on first filledUpTo touch")
        .isTrue();
  }

  /**
   * Second {@code physicalSize} call for the same {@code fileId} on a real AOBT must hit the
   * existing-entry arm of {@code AtomicOperationBinaryTracking.filledUpTo} (the arm that returns
   * {@code maxNewPageIndex + 1} when {@code isNew || maxNewPageIndex > -2}) and must preserve
   * the placeholder identity rather than replacing it. Layer B sites that share a TX (e.g.,
   * {@code CollectionPositionMapV2.create} + subsequent in-TX operations) depend on this arm:
   * the second call must observe the in-TX horizon, not re-fall-through to the cache.
   *
   * <p>The first call exercises the missing-entry arm (registers a placeholder, returns the
   * cache value via {@code WriteCache.getFilledUpTo}). Reflection mutates {@code maxNewPageIndex}
   * on the registered placeholder so the existing-entry arm fires on the second call. The
   * placeholder must NOT be replaced — placeholder identity is asserted via {@code isSameAs}.
   * A regression that re-created the placeholder on the second call (or bypassed AOBT) would
   * fail this test.
   */
  @Test
  public void physicalSizeSecondCallHitsExistingEntryArm() throws Exception {
    AtomicOperation op = newRealAobt();
    when(mockWriteCache.getFilledUpTo(FILE_ID)).thenReturn(0L);

    // First call: missing-entry arm — registers placeholder, falls through to cache (= 0).
    long first = component.callPhysicalSize(
        op, FILE_ID, StorageComponent.PhysicalReadIntent.BOOTSTRAP_EMPTINESS_CHECK);
    assertThat(first)
        .as("first call falls through to cache via missing-entry arm")
        .isEqualTo(0L);

    Map<Long, ?> fc = fileChangesOf(op);
    Object placeholder = fc.get(FILE_ID);
    assertThat(placeholder).as("placeholder registered on first call").isNotNull();

    // Mutate maxNewPageIndex on the placeholder so the existing-entry arm fires next.
    // AOBT.filledUpTo's middle arm returns (maxNewPageIndex + 1) when isNew || maxNewPageIndex > -2.
    Field maxNewIdx = placeholder.getClass().getDeclaredField("maxNewPageIndex");
    maxNewIdx.setAccessible(true);
    maxNewIdx.setLong(placeholder, 4L);

    // Second call: existing-entry arm — observes (4 + 1) = 5 from in-TX state, NOT a re-read
    // of the cache (which still returns 0). The placeholder identity must be preserved.
    long second = component.callPhysicalSize(
        op, FILE_ID, StorageComponent.PhysicalReadIntent.BOOTSTRAP_EMPTINESS_CHECK);
    assertThat(second)
        .as("second call must hit existing-entry arm and observe maxNewPageIndex + 1")
        .isEqualTo(5L);
    assertThat(fileChangesOf(op).get(FILE_ID))
        .as("placeholder identity preserved across calls")
        .isSameAs(placeholder);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a real {@code AtomicOperationBinaryTracking} via its package-private constructor.
   * Both the class and the {@code fileChanges} field we want to inspect are package-private to
   * the atomic-operations package; this test lives in {@code ...paginated.base} so reflection
   * is the only door. Every collaborator that AOBT touches in {@code filledUpTo} is either the
   * test's {@link #mockWriteCache} or an empty concurrent map.
   */
  private AtomicOperation newRealAobt() throws Exception {
    Class<?> aobtClass = Class.forName(AOBT_CLASS_NAME);
    Constructor<?> ctor =
        aobtClass.getDeclaredConstructor(
            ReadCache.class,
            WriteCache.class,
            WriteAheadLog.class,
            int.class,
            AtomicOperationsSnapshot.class,
            ConcurrentSkipListMap.class,
            ConcurrentSkipListMap.class,
            AtomicLong.class,
            ConcurrentSkipListMap.class,
            ConcurrentSkipListMap.class,
            AtomicLong.class);
    ctor.setAccessible(true);
    return (AtomicOperation) ctor.newInstance(
        mockReadCache,
        mockWriteCache,
        mock(WriteAheadLog.class),
        STORAGE_ID,
        new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 100),
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong(),
        new ConcurrentSkipListMap<>(),
        new ConcurrentSkipListMap<>(),
        new AtomicLong());
  }

  /**
   * Reflects out AOBT's private {@code fileChanges} map. The field is declared on
   * {@code AtomicOperationBinaryTracking}; the {@code containsKey(long)} primitive method on
   * {@code Long2ObjectOpenHashMap} is exposed through {@link Map}'s generic surface here by
   * boxing — we only need to ask "is the {@code fileId} present?".
   */
  @SuppressWarnings("unchecked")
  private static Map<Long, ?> fileChangesOf(AtomicOperation op) throws Exception {
    Class<?> aobtClass = Class.forName(AOBT_CLASS_NAME);
    Field f = aobtClass.getDeclaredField("fileChanges");
    f.setAccessible(true);
    return (Map<Long, ?>) f.get(op);
  }

  private static long composeFileId(long fileId, int storageId) {
    return (((long) storageId) << 32) | fileId;
  }

  /**
   * Trivial subclass exposing {@code physicalSize} to the test. Matches the same shape used by
   * {@code StorageComponentOptimisticReadTest} so future test maintainers see one consistent
   * pattern for protected-method coverage on this class.
   */
  private static class TestStorageComponent extends StorageComponent {
    TestStorageComponent(AbstractStorage storage) {
      super(storage, "test", ".tst", "test.lock", true);
    }

    long callPhysicalSize(AtomicOperation op, long fileId, PhysicalReadIntent intent) {
      return physicalSize(op, fileId, intent);
    }
  }
}
