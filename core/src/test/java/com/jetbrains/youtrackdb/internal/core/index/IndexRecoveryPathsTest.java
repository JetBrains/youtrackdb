package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.exception.StaleIndexEngineException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;
import org.mockito.Answers;

/** Covers owner-bound recovery for every converted index path with one table per index class. */
public class IndexRecoveryPathsTest {

  private static final int VALID_IDENTIFIER = 5;
  private static final int STALE_IDENTIFIER = 1_000_005;
  private static final RecordId OWNER = new RecordId(20, 1);
  private static final RecordId FOREIGN_OWNER = new RecordId(20, 2);

  @Test
  public void indexOneValueRecoveryPathsUseOwnerAndRejectForeignOwner() throws Exception {
    verifyPaths(IndexUnique::new, indexOneValuePaths());
  }

  /**
   * Every single-value read path permits two owner-bound retries. If both resolved engines are
   * stale, the path must stop after three attempts and report the descriptor instead of looping.
   */
  @Test(timeout = 10_000)
  public void indexOneValuePersistentStalenessFailsAfterBoundedOwnerRecovery() throws Exception {
    for (var path : indexOneValuePaths()) {
      var fixture = fixture(IndexUnique::new, path.storageMethod(), true);

      var exception =
          assertThrows(
              path.name() + " must fail after bounded owner recovery",
              StaleIndexEngineException.class,
              () -> path.operation().invoke(fixture));

      assertTrue(
          path.name() + " must explain the bounded retry",
          exception.getMessage().contains("remained stale after owner-bound recovery"));
      assertTrue(
          path.name() + " must identify the descriptor owner",
          exception.getMessage().contains(OWNER.toString()));
      assertEquals(
          path.name() + " must install the owner-resolved identifier before failing closed",
          VALID_IDENTIFIER,
          fixture.index.getIndexId());
      assertEquals(
          path.name() + " must resolve the owner exactly twice",
          2,
          invocationCount(fixture.storage, "resolveIndexEngineByOwner"));
      assertEquals(
          path.name() + " must attempt the storage operation exactly three times",
          3,
          invocationCount(fixture.storage, path.storageMethod()));
    }
  }

  private static List<RecoveryPath> indexOneValuePaths() {
    return List.of(
        path("getRidsIgnoreTx", "getIndexValues",
            fixture -> fixture.index.getRidsIgnoreTx(fixture.session, "key").close()),
        path("streamEntries", "getIndexValues",
            fixture -> fixture.index.streamEntries(fixture.session, List.of("key"), true)
                .toList()),
        path("streamEntriesBetween", "iterateIndexEntriesBetween",
            fixture -> fixture.index.streamEntriesBetween(
                fixture.session, "a", true, "z", true, true).close()),
        path("streamEntriesMajor", "iterateIndexEntriesMajor",
            fixture -> fixture.index.streamEntriesMajor(
                fixture.session, "a", true, true).close()),
        path("streamEntriesMinor", "iterateIndexEntriesMinor",
            fixture -> fixture.index.streamEntriesMinor(
                fixture.session, "z", true, true).close()),
        path("size", "getIndexSize", fixture -> fixture.index.size(fixture.session)),
        path("stream", "getIndexStream",
            fixture -> fixture.index.stream(fixture.session).close()),
        path("descStream", "getIndexDescStream",
            fixture -> fixture.index.descStream(fixture.session).close()));
  }

  /** Every single-value stream and range path reports a detached carrier as typed staleness. */
  @Test
  public void indexOneValueDetachedReadPathsFailWithTypedStaleness() throws Exception {
    verifyDetachedPaths(IndexUnique::new, indexOneValuePaths());
  }

  @Test
  public void indexMultiValuesRecoveryPathsUseOwnerAndRejectForeignOwner() throws Exception {
    verifyPaths(IndexNotUnique::new, indexMultiValuesPaths());
  }

  /** Every multi-value stream and range path reports a detached carrier as typed staleness. */
  @Test
  public void indexMultiValuesDetachedReadPathsFailWithTypedStaleness() throws Exception {
    verifyDetachedPaths(IndexNotUnique::new, indexMultiValuesPaths());
  }

  /** Every multi-value read path stops after three persistently stale engine attempts. */
  @Test(timeout = 10_000)
  public void indexMultiValuesPersistentStalenessFailsAfterBoundedOwnerRecovery()
      throws Exception {
    for (var path : indexMultiValuesPaths()) {
      var fixture = fixture(IndexNotUnique::new, path.storageMethod(), true);

      var exception = assertThrows(
          path.name() + " must fail after bounded owner recovery",
          StaleIndexEngineException.class,
          () -> path.operation().invoke(fixture));

      assertTrue(path.name() + " must explain the bounded retry",
          exception.getMessage().contains("remained stale after owner-bound recovery"));
      assertTrue(path.name() + " must identify the descriptor owner",
          exception.getMessage().contains(OWNER.toString()));
      assertEquals(path.name() + " must retain the resolved identifier",
          VALID_IDENTIFIER, fixture.index.getIndexId());
      assertEquals(path.name() + " must resolve the owner exactly twice",
          2, invocationCount(fixture.storage, "resolveIndexEngineByOwner"));
      assertEquals(path.name() + " must attempt storage exactly three times",
          3, invocationCount(fixture.storage, path.storageMethod()));
    }
  }

  private static List<RecoveryPath> indexMultiValuesPaths() {
    return List.of(
        path("getRidsIgnoreTx", "getIndexValues",
            fixture -> fixture.index.getRidsIgnoreTx(fixture.session, "key").close()),
        path("streamEntriesBetween", "iterateIndexEntriesBetween",
            fixture -> fixture.index.streamEntriesBetween(
                fixture.session, "a", true, "z", true, true).close()),
        path("streamEntriesMajor", "iterateIndexEntriesMajor",
            fixture -> fixture.index.streamEntriesMajor(
                fixture.session, "a", true, true).close()),
        path("streamEntriesMinor", "iterateIndexEntriesMinor",
            fixture -> fixture.index.streamEntriesMinor(
                fixture.session, "z", true, true).close()),
        path("streamForKey", "getIndexValues",
            fixture -> fixture.index.streamEntries(fixture.session, List.of("key"), true)
                .toList()),
        path("size", "getIndexSize", fixture -> fixture.index.size(fixture.session)),
        path("stream", "getIndexStream",
            fixture -> fixture.index.stream(fixture.session).close()),
        path("descStream", "getIndexDescStream",
            fixture -> fixture.index.descStream(fixture.session).close()));
  }

  /** The state-lock form also exhausts after three persistent stale resolutions. */
  @Test(timeout = 10_000)
  public void stateLockRecoveryFailsAfterBoundedOwnerRecovery() {
    var fixture = fixture(IndexNotUnique::new, "getIndexEngineWithStateLock", true);

    var exception = assertThrows(
        StaleIndexEngineException.class,
        () -> fixture.index.acquireAtomicExclusiveLock(fixture.atomicOperation));

    assertTrue(exception.getMessage().contains("remained stale after owner-bound recovery"));
    assertEquals(2,
        invocationCount(fixture.storage, "resolveIndexEngineByOwnerWithStateLock"));
    assertEquals(3,
        invocationCount(fixture.storage, "getIndexEngineWithStateLock"));
  }

  @Test
  public void indexAbstractRecoveryPathsUseOwnerAndRejectForeignOwner() throws Exception {
    verifyPaths(IndexNotUnique::new, indexAbstractPaths());
  }

  /** Rebuild-tail helpers convert detached carriers before storage extracts an identifier. */
  @Test
  public void indexAbstractRebuildTailRejectsDetachedCarrierWithTypedStaleness()
      throws Exception {
    verifyDetachedPaths(
        IndexNotUnique::new,
        indexAbstractPaths().stream()
            .filter(path -> path.name().equals("setBulkLoading")
                || path.name().equals("buildHistogramAfterFill"))
            .toList());
  }

  private static List<RecoveryPath> indexAbstractPaths() {
    return List.of(
        path("setBulkLoading", "getIndexEngine",
            fixture -> invokePrivate(fixture.index, "setBulkLoading", boolean.class, true)),
        path("buildHistogramAfterFill", "getIndexEngine",
            fixture -> invokePrivate(fixture.index, "buildHistogramAfterFill")),
        path("doDelete", "deleteIndexEngine",
            fixture -> fixture.index.doDelete(fixture.transaction)),
        path("keyStream", "getIndexKeyStream",
            fixture -> fixture.index.keyStream(fixture.atomicOperation).close()),
        path("acquireAtomicExclusiveLock", "getIndexEngineWithStateLock",
            fixture -> fixture.index.acquireAtomicExclusiveLock(fixture.atomicOperation)),
        path("getStatistics", "getIndexEngine",
            fixture -> fixture.index.getStatistics(fixture.session)),
        path("getHistogram", "getIndexEngine",
            fixture -> fixture.index.getHistogram(fixture.session)),
        path("analyzeHistogram", "getIndexEngine",
            fixture -> fixture.index.analyzeHistogram(fixture.session)),
        path("onIndexEngineChange", "callIndexEngine",
            fixture -> fixture.index.onIndexEngineChange(
                fixture.session, fixture.index.state())));
  }

  private static void verifyPaths(IndexFactory factory, List<RecoveryPath> paths) throws Exception {
    for (var path : paths) {
      var recoveringFixture = fixture(factory, path.storageMethod());
      path.operation().invoke(recoveringFixture);
      final var expectedIdentifier = path.name().equals("doDelete") ? -1 : VALID_IDENTIFIER;
      assertEquals(path.name() + " must publish its final carrier shape",
          expectedIdentifier, recoveringFixture.index.getIndexId());

      var foreignFixture = fixture(factory, path.storageMethod());
      foreignFixture.index.setHandleStateForTest(
          foreignFixture.index.getIndexId(), FOREIGN_OWNER);
      assertThrows(
          path.name() + " must reject a foreign owner",
          StaleIndexEngineException.class,
          () -> path.operation().invoke(foreignFixture));
    }
  }

  private static void verifyDetachedPaths(IndexFactory factory, List<RecoveryPath> paths)
      throws Exception {
    for (var path : paths) {
      var fixture = fixture(factory, path.storageMethod());
      fixture.index.setHandleStateForTest(-1, OWNER);

      assertThrows(
          path.name() + " must report detached carrier staleness",
          StaleIndexEngineException.class,
          () -> path.operation().invoke(fixture));
      assertEquals(
          path.name() + " must fail before unchecked storage identifier extraction",
          0,
          invocationCount(fixture.storage, path.storageMethod()));
    }
  }

  private static Fixture fixture(IndexFactory factory, String staleStorageMethod) {
    return fixture(factory, staleStorageMethod, false);
  }

  private static Fixture fixture(
      IndexFactory factory, String staleStorageMethod, boolean persistentlyStale) {
    var engine = mock(BaseIndexEngine.class);
    var storage = mock(AbstractStorage.class, invocation -> {
      var methodName = invocation.getMethod().getName();
      if (methodName.equals("getName")) {
        return "recovery-path-test";
      }
      if (methodName.startsWith("resolveIndexEngineByOwner")) {
        var owner = invocation.getArgument(0);
        if (OWNER.equals(owner)) {
          return new AbstractStorage.ResolvedIndexEngine(VALID_IDENTIFIER, null);
        }
        throw new StaleIndexEngineException(
            "recovery-path-test", "No engine belongs to foreign owner " + owner);
      }
      if (methodName.equals(staleStorageMethod)
          && (persistentlyStale
              || Stream.of(invocation.getArguments())
                  .anyMatch(argument -> Integer.valueOf(STALE_IDENTIFIER).equals(argument)))) {
        throw new InvalidIndexEngineIdException("stale identifier");
      }
      if (methodName.equals("getIndexEngine")
          || methodName.equals("getIndexEngineWithStateLock")) {
        return engine;
      }
      if (Stream.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
        return Stream.empty();
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });

    var session = mock(DatabaseSessionEmbedded.class);
    var transaction = mock(FrontendTransactionImpl.class);
    var atomicOperation = mock(AtomicOperation.class);
    var entity = mock(EntityImpl.class);
    when(session.getActiveTransaction()).thenReturn(transaction);
    when(session.getTransactionInternal()).thenReturn(transaction);
    when(transaction.getDatabaseSession()).thenReturn(session);
    when(transaction.getAtomicOperation()).thenReturn(atomicOperation);
    when(transaction.loadEntity(any())).thenReturn(entity);

    var metadata = mock(IndexMetadata.class);
    var definition = mock(IndexDefinition.class);
    when(metadata.getName()).thenReturn("recovery-index");
    when(metadata.getIndexDefinition()).thenReturn(definition);
    when(definition.getClassName()).thenReturn(null);
    when(definition.getCollate()).thenReturn(new DefaultCollate());

    var index = factory.create(storage);
    index.im = metadata;
    index.setHandleStateForTest(STALE_IDENTIFIER, OWNER);
    return new Fixture(index, session, transaction, atomicOperation, storage);
  }

  private static int invocationCount(AbstractStorage storage, String methodName) {
    return (int) mockingDetails(storage).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals(methodName))
        .count();
  }

  private static RecoveryPath path(
      String name, String storageMethod, RecoveryOperation operation) {
    return new RecoveryPath(name, storageMethod, operation);
  }

  private static void invokePrivate(IndexAbstract index, String methodName) throws Exception {
    invokePrivate(index, methodName, null, null);
  }

  private static void invokePrivate(
      IndexAbstract index, String methodName, Class<?> parameterType, Object argument)
      throws Exception {
    var method = parameterType == null
        ? IndexAbstract.class.getDeclaredMethod(methodName)
        : IndexAbstract.class.getDeclaredMethod(methodName, parameterType);
    method.setAccessible(true);
    try {
      if (parameterType == null) {
        method.invoke(index);
      } else {
        method.invoke(index, argument);
      }
    } catch (InvocationTargetException exception) {
      if (exception.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw exception;
    }
  }

  private record Fixture(
      IndexAbstract index,
      DatabaseSessionEmbedded session,
      FrontendTransactionImpl transaction,
      AtomicOperation atomicOperation,
      AbstractStorage storage) {
  }

  private record RecoveryPath(
      String name, String storageMethod, RecoveryOperation operation) {
  }

  @FunctionalInterface
  private interface IndexFactory {

    IndexAbstract create(AbstractStorage storage);
  }

  @FunctionalInterface
  private interface RecoveryOperation {

    void invoke(Fixture fixture) throws Exception;
  }
}
