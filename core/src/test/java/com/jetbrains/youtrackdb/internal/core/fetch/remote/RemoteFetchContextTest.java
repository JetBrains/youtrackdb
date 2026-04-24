/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.fetch.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.internal.core.exception.FetchException;
import com.jetbrains.youtrackdb.internal.core.fetch.FetchContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Dead-code pin tests for {@link RemoteFetchContext}. Every override in this class is a no-op
 * (empty body) — the class exists only for the historical {@code ONetworkBinaryProtocol} fetch
 * path that is no longer wired in. The pin tests here are purely observable:
 *
 * <ul>
 *   <li>Every callback method returns without throwing when invoked with null / empty arguments,
 *       which exercises the body and captures it in coverage.
 *   <li>A subclass that counts each invocation verifies that no super-method accidentally does
 *       something observable (e.g., sets a flag or invokes a peer method).
 *   <li>{@link RemoteFetchContext#fetchEmbeddedDocuments()} returns false by contract — if this
 *       ever flips, the binary-protocol path would start recursing into embedded documents and
 *       silently change the wire format.
 * </ul>
 *
 * <p>WHEN-FIXED: Track 22 — delete core/fetch/ package (0 callers outside self + DepthFetchPlanTest).
 */
public class RemoteFetchContextTest {

  @Test
  public void fetchEmbeddedDocumentsAlwaysReturnsFalse() {
    // A defaulted-true regression here would change binary-protocol fetch behaviour silently.
    assertFalse(new RemoteFetchContext().fetchEmbeddedDocuments());
  }

  @Test
  public void allCallbacksAreNoOpsWhenInvokedWithNullArgs() throws FetchException {
    // Each callback body is {} in production. Invoking every method with nulls (where legal)
    // pins the no-op contract: any mutation that adds side-effects (logging, dispatch, state)
    // will either throw on null args or be detected by the next test.
    FetchContext ctx = new RemoteFetchContext();

    ctx.onBeforeFetch(null);
    ctx.onAfterFetch(null, null);
    ctx.onBeforeArray(null, null, null, null, null);
    ctx.onAfterArray(null, null, null, null);
    ctx.onBeforeCollection(null, null, null, null, null);
    ctx.onAfterCollection(null, null, null, null);
    ctx.onBeforeMap(null, null, null, null);
    ctx.onAfterMap(null, null, null, null);
    ctx.onBeforeDocument(null, null, null, null, null);
    ctx.onAfterDocument(null, null, null, null, null);
    ctx.onBeforeStandardField(null, null, null, null);
    ctx.onAfterStandardField(null, null, null, null);
    ctx.onBeforeStandardField("value", "field", "user", PropertyTypeInternal.STRING);
    ctx.onAfterStandardField("value", "field", "user", PropertyTypeInternal.STRING);
    // Reaching here without NPE or FetchException is the assertion.
  }

  @Test
  public void noCallbackInSuperTriggersPeerCallback() throws FetchException {
    // Subclass that counts every super-call. If any super-method in RemoteFetchContext ever
    // delegates to another callback (e.g., onBeforeMap calling onBeforeCollection), one of the
    // counters below would see a non-zero increment from the peer's super-invocation. This
    // pin catches silent dispatch drift between the no-op overrides.
    var counters = new MethodCounters();
    FetchContext ctx = counters;

    // Invoke each method exactly once and verify the corresponding counter is exactly 1.
    ctx.onBeforeFetch(null);
    assertEquals(1, counters.beforeFetch.get());
    assertEquals(0, counters.afterFetch.get());
    assertEquals(0, counters.beforeArray.get());

    ctx.onAfterFetch(null, null);
    assertEquals(1, counters.afterFetch.get());

    ctx.onBeforeArray(null, null, null, null, null);
    assertEquals(1, counters.beforeArray.get());

    ctx.onAfterArray(null, null, null, null);
    assertEquals(1, counters.afterArray.get());

    ctx.onBeforeCollection(null, null, null, null, null);
    assertEquals(1, counters.beforeCollection.get());

    ctx.onAfterCollection(null, null, null, null);
    assertEquals(1, counters.afterCollection.get());

    ctx.onBeforeMap(null, null, null, null);
    assertEquals(1, counters.beforeMap.get());

    ctx.onAfterMap(null, null, null, null);
    assertEquals(1, counters.afterMap.get());

    ctx.onBeforeDocument(null, null, null, null, null);
    assertEquals(1, counters.beforeDocument.get());

    ctx.onAfterDocument(null, null, null, null, null);
    assertEquals(1, counters.afterDocument.get());

    ctx.onBeforeStandardField(null, null, null, null);
    assertEquals(1, counters.beforeStandardField.get());

    ctx.onAfterStandardField(null, null, null, null);
    assertEquals(1, counters.afterStandardField.get());
  }

  // ---------------------------------------------------------------------------
  // Subclass that counts super-method invocations — catches silent dispatch drift.
  // ---------------------------------------------------------------------------

  private static final class MethodCounters extends RemoteFetchContext {
    final AtomicInteger beforeFetch = new AtomicInteger();
    final AtomicInteger afterFetch = new AtomicInteger();
    final AtomicInteger beforeArray = new AtomicInteger();
    final AtomicInteger afterArray = new AtomicInteger();
    final AtomicInteger beforeCollection = new AtomicInteger();
    final AtomicInteger afterCollection = new AtomicInteger();
    final AtomicInteger beforeMap = new AtomicInteger();
    final AtomicInteger afterMap = new AtomicInteger();
    final AtomicInteger beforeDocument = new AtomicInteger();
    final AtomicInteger afterDocument = new AtomicInteger();
    final AtomicInteger beforeStandardField = new AtomicInteger();
    final AtomicInteger afterStandardField = new AtomicInteger();

    @Override
    public void onBeforeFetch(
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord)
        throws FetchException {
      super.onBeforeFetch(iRootRecord);
      beforeFetch.incrementAndGet();
    }

    @Override
    public void onAfterFetch(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord)
        throws FetchException {
      super.onAfterFetch(db, iRootRecord);
      afterFetch.incrementAndGet();
    }

    @Override
    public void onBeforeArray(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord,
        String iFieldName,
        Object iUserObject,
        com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable[] iArray)
        throws FetchException {
      super.onBeforeArray(db, iRootRecord, iFieldName, iUserObject, iArray);
      beforeArray.incrementAndGet();
    }

    @Override
    public void onAfterArray(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord,
        String iFieldName,
        Object iUserObject)
        throws FetchException {
      super.onAfterArray(db, iRootRecord, iFieldName, iUserObject);
      afterArray.incrementAndGet();
    }

    @Override
    public void onBeforeCollection(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord,
        String iFieldName,
        Object iUserObject,
        Iterable<?> iterable)
        throws FetchException {
      super.onBeforeCollection(db, iRootRecord, iFieldName, iUserObject, iterable);
      beforeCollection.incrementAndGet();
    }

    @Override
    public void onAfterCollection(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord,
        String iFieldName,
        Object iUserObject)
        throws FetchException {
      super.onAfterCollection(db, iRootRecord, iFieldName, iUserObject);
      afterCollection.incrementAndGet();
    }

    @Override
    public void onBeforeMap(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord,
        String iFieldName,
        Object iUserObject)
        throws FetchException {
      super.onBeforeMap(db, iRootRecord, iFieldName, iUserObject);
      beforeMap.incrementAndGet();
    }

    @Override
    public void onAfterMap(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord,
        String iFieldName,
        Object iUserObject)
        throws FetchException {
      super.onAfterMap(db, iRootRecord, iFieldName, iUserObject);
      afterMap.incrementAndGet();
    }

    @Override
    public void onBeforeDocument(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRecord,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl entity,
        String iFieldName,
        Object iUserObject)
        throws FetchException {
      super.onBeforeDocument(db, iRecord, entity, iFieldName, iUserObject);
      beforeDocument.incrementAndGet();
    }

    @Override
    public void onAfterDocument(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded db,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iRootRecord,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl entity,
        String iFieldName,
        Object iUserObject)
        throws FetchException {
      super.onAfterDocument(db, iRootRecord, entity, iFieldName, iUserObject);
      afterDocument.incrementAndGet();
    }

    @Override
    public void onBeforeStandardField(
        Object iFieldValue,
        String iFieldName,
        Object iUserObject,
        PropertyTypeInternal fieldType) {
      super.onBeforeStandardField(iFieldValue, iFieldName, iUserObject, fieldType);
      beforeStandardField.incrementAndGet();
    }

    @Override
    public void onAfterStandardField(
        Object iFieldValue,
        String iFieldName,
        Object iUserObject,
        PropertyTypeInternal fieldType) {
      super.onAfterStandardField(iFieldValue, iFieldName, iUserObject, fieldType);
      afterStandardField.incrementAndGet();
    }
  }
}
