package com.jetbrains.youtrackdb.internal.core.storage.index.engine;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Smoke-pin tests for {@link RemoteIndexEngine} — the stub index engine used on
 * remote (non-embedded) database connections. Verifies that the engine returns the
 * correct id and name, that no-op methods run without exceptions, that query methods
 * correctly return null / false / 0, and that unsupported streaming and locking
 * operations throw {@link UnsupportedOperationException} as documented.
 */
public class RemoteIndexEngineTest {

  private RemoteIndexEngine engine;

  @Before
  public void setUp() {
    engine = new RemoteIndexEngine(42, "testIndex");
  }

  /**
   * {@link RemoteIndexEngine#getId()} returns the id supplied to the constructor.
   */
  @Test
  public void getIdReturnsConstructorId() {
    Assert.assertEquals(42, engine.getId());
  }

  /**
   * {@link RemoteIndexEngine#getName()} returns the name supplied to the constructor.
   */
  @Test
  public void getNameReturnsConstructorName() {
    Assert.assertEquals("testIndex", engine.getName());
  }

  /**
   * No-op lifecycle methods ({@code init}, {@code flush}, {@code create}, {@code delete},
   * {@code load}, {@code clear}, {@code close}) complete without throwing.
   */
  @Test
  public void noOpLifecycleMethodsDoNotThrow() throws Exception {
    engine.init(null, null);
    engine.flush();
    engine.close();
    // create, delete, load, clear, remove accept null AtomicOperation on the stub
    engine.create(null, null);
    engine.delete(null);
    engine.load(null, null);
    engine.clear(null, null);
    engine.remove(null, null, "key");
  }

  /**
   * {@link RemoteIndexEngine#get(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded, Object)}
   * returns null — the remote stub has no local state to retrieve.
   */
  @Test
  public void getReturnsNull() {
    Assert.assertNull(engine.get(null, "someKey"));
  }

  /**
   * {@link RemoteIndexEngine#validatedPut} returns {@code false} — the remote stub
   * rejects all put operations.
   */
  @Test
  public void validatedPutReturnsFalse() {
    Assert.assertFalse(engine.validatedPut(null, "key", null, null));
  }

  /**
   * {@link RemoteIndexEngine#size} returns 0 — the remote stub reports no entries.
   */
  @Test
  public void sizeReturnsZero() {
    Assert.assertEquals(0L, engine.size(null, null, null));
  }

  /**
   * {@link RemoteIndexEngine#put} and {@link RemoteIndexEngine#update} complete without
   * throwing — they are no-ops on the remote stub.
   */
  @Test
  public void putAndUpdateAreNoOps() {
    engine.put(null, null, "key", "value");
    engine.update(null, null, "key", null);
  }

  /**
   * {@link RemoteIndexEngine#iterateEntriesBetween} throws
   * {@link UnsupportedOperationException} — streaming is not available on a remote stub.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void iterateEntriesBetweenThrowsUnsupported() {
    engine.iterateEntriesBetween(null, true, null, true, true, null, null);
  }

  /**
   * {@link RemoteIndexEngine#iterateEntriesMajor} throws
   * {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void iterateEntriesMajorThrowsUnsupported() {
    engine.iterateEntriesMajor(null, true, true, null, null);
  }

  /**
   * {@link RemoteIndexEngine#iterateEntriesMinor} throws
   * {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void iterateEntriesMinorThrowsUnsupported() {
    engine.iterateEntriesMinor(null, true, true, null, null);
  }

  /**
   * {@link RemoteIndexEngine#stream} throws {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void streamThrowsUnsupported() {
    engine.stream(null, null);
  }

  /**
   * {@link RemoteIndexEngine#descStream} throws {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void descStreamThrowsUnsupported() {
    engine.descStream(null, null);
  }

  /**
   * {@link RemoteIndexEngine#keyStream} throws {@link UnsupportedOperationException}.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void keyStreamThrowsUnsupported() {
    engine.keyStream(null);
  }

  /**
   * {@link RemoteIndexEngine#acquireAtomicExclusiveLock} throws
   * {@link UnsupportedOperationException} — atomic locking is not supported on a
   * remote index engine.
   */
  @Test(expected = UnsupportedOperationException.class)
  public void acquireAtomicExclusiveLockThrowsUnsupported() {
    engine.acquireAtomicExclusiveLock(null);
  }
}
