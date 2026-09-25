package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.Before;
import org.junit.Test;

/** Tests mode-aware component locks retained until an atomic operation completes. */
public class AtomicOperationsManagerComponentLockModeTest {

  private AtomicOperationsManager manager;
  private AtomicOperation operation;
  private StorageComponent component;

  @Before
  public void setUp() {
    final var storage = mock(AbstractStorage.class);
    when(storage.getWALInstance()).thenReturn(mock(WriteAheadLog.class));
    when(storage.getReadCache()).thenReturn(mock(ReadCache.class));
    when(storage.getWriteCache()).thenReturn(mock(WriteCache.class));
    when(storage.getIdGen()).thenReturn(mock(AtomicOperationIdGen.class));
    manager = new AtomicOperationsManager(storage, mock(AtomicOperationsTable.class));

    operation = mock(AtomicOperation.class);
    component = mock(StorageComponent.class);
    when(component.getLockName()).thenReturn("component-lock");
  }

  /** A first shared request acquires and records shared mode for operation-end release. */
  @Test
  public void sharedAcquisitionRecordsItsMode() {
    manager.acquireSharedLockTillOperationComplete(operation, component);

    verify(component).lockShared();
    verify(operation).addLockedComponent(component);
    verify(operation)
        .addLockedObject("component-lock", AtomicOperation.ComponentLockMode.SHARED);
  }

  /** Operation cleanup releases a shared lock through the shared side of the component lock. */
  @Test
  public void cleanupReleasesSharedMode() {
    final var components = new ArrayList<StorageComponent>();
    components.add(component);
    when(operation.lockedComponents()).thenReturn(components);
    when(operation.lockedObjects()).thenReturn(new HashSet<>(java.util.Set.of("component-lock")));
    when(operation.lockedObjectMode("component-lock"))
        .thenReturn(AtomicOperation.ComponentLockMode.SHARED);
    when(component.isSharedOwner()).thenReturn(true);

    manager.ensureThatComponentsUnlocked(operation);

    verify(component).unlockShared();
    verify(component, never()).unlockExclusive();
  }

  /** A shared-to-exclusive upgrade fails before entering the non-upgradable component lock. */
  @Test
  public void sharedToExclusiveUpgradeFailsFast() {
    when(operation.lockedObjectMode("component-lock"))
        .thenReturn(AtomicOperation.ComponentLockMode.SHARED);

    assertThatThrownBy(
        () -> manager.acquireExclusiveLockTillOperationComplete(operation, component))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("component-lock")
        .hasMessageContaining("SHARED")
        .hasMessageContaining("EXCLUSIVE");

    verify(component, never()).lockExclusive();
  }

  /** Cleanup clears the mode map, so the same operation takes a released lock again. */
  @Test
  public void releasedExclusiveLockCanBeAcquiredAgain() {
    final var modes = new HashMap<String, AtomicOperation.ComponentLockMode>();
    final var components = new ArrayList<StorageComponent>();
    when(operation.lockedObjectMode("component-lock"))
        .thenAnswer(ignored -> modes.get("component-lock"));
    doAnswer(
        invocation -> {
          modes.put(invocation.getArgument(0), invocation.getArgument(1));
          return null;
        })
        .when(operation)
        .addLockedObject(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(AtomicOperation.ComponentLockMode.class));
    doAnswer(
        invocation -> {
          components.add(invocation.getArgument(0));
          return null;
        })
        .when(operation)
        .addLockedComponent(component);
    when(operation.lockedComponents()).thenReturn(components);
    when(operation.lockedObjects()).thenReturn(modes.keySet());
    when(component.isExclusiveOwner()).thenReturn(true);

    manager.acquireExclusiveLockTillOperationComplete(operation, component);
    manager.ensureThatComponentsUnlocked(operation);
    manager.acquireExclusiveLockTillOperationComplete(operation, component);

    verify(component, times(2)).lockExclusive();
  }

  /** One failed shared release does not prevent later component locks from being released. */
  @Test
  public void failedSharedReleaseStillDrainsRemainingLocks() {
    final var first = mock(StorageComponent.class);
    final var second = mock(StorageComponent.class);
    when(first.getLockName()).thenReturn("first");
    when(second.getLockName()).thenReturn("second");
    when(first.isSharedOwner()).thenReturn(true);
    when(second.isSharedOwner()).thenReturn(true);
    final var releaseFailure = new IllegalMonitorStateException("first release failed");
    doThrow(releaseFailure).when(first).unlockShared();
    doThrow(releaseFailure).when(second).unlockShared();
    when(operation.lockedComponents())
        .thenReturn(new ArrayList<>(java.util.List.of(first, second)));
    when(operation.lockedObjects()).thenReturn(new HashSet<>(java.util.Set.of("first", "second")));
    when(operation.lockedObjectMode("first"))
        .thenReturn(AtomicOperation.ComponentLockMode.SHARED);
    when(operation.lockedObjectMode("second"))
        .thenReturn(AtomicOperation.ComponentLockMode.SHARED);

    assertThatThrownBy(() -> manager.ensureThatComponentsUnlocked(operation))
        .isInstanceOf(IllegalMonitorStateException.class)
        .hasMessageContaining("first release failed");

    verify(second).unlockShared();
  }

  /** An exclusive lock satisfies a later shared request without changing recorded mode. */
  @Test
  public void exclusiveModeSatisfiesSharedRequest() {
    when(operation.lockedObjectMode("component-lock"))
        .thenReturn(AtomicOperation.ComponentLockMode.EXCLUSIVE);

    manager.acquireSharedLockTillOperationComplete(operation, component);

    verify(component, never()).lockShared();
    verify(operation, never()).addLockedComponent(component);
  }
}
