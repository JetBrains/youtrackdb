/*
 *
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
 *
 */
package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

/**
 * Shape pin for {@link HookReplacedRecordThreadLocal}. PSI all-scope {@code ReferencesSearch}
 * confirms zero references outside this class's own source file across all five Maven
 * modules; the {@code static volatile INSTANCE} field is never read by production code,
 * and {@link #getIfDefined()} / {@link #isDefined()} have no callers either. The class is
 * a {@link ThreadLocal}-backed scratch space drafted for a hook-replay feature that never
 * landed.
 *
 * <p>This pin runs under {@link SequentialTest} because it touches the static
 * {@code INSTANCE} reference. Other tests in the suite that touch engines-manager state
 * (e.g. anything driving {@code YouTrackDBEnginesManager.shutdown()}) could race with our
 * INSTANCE reads since the static initializer registers a shutdown listener that nulls
 * INSTANCE — sequential execution avoids the race.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this class and this test file together.
 * No production callers exist, and the parent {@link ThreadLocal} surface is sufficient
 * for any hypothetical revival.
 */
@Category(SequentialTest.class)
public class HookReplacedRecordThreadLocalDeadCodeTest {

  @After
  public void clearAnyThreadLocalLeakage() {
    // Defensive: a future test on the same surefire worker thread must not see lingering
    // state from this one. Calling remove() on a null INSTANCE would NPE — guard it so the
    // teardown is robust even if the engine shutdown listener has nulled INSTANCE between
    // our test body and this @After callback (unlikely under SequentialTest but cheap).
    var instance = HookReplacedRecordThreadLocal.INSTANCE;
    if (instance != null) {
      instance.remove();
    }
  }

  @Test
  public void classIsPublicNonAbstractThreadLocalSubtype() {
    var clazz = HookReplacedRecordThreadLocal.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertFalse("must not be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertTrue("must extend ThreadLocal", ThreadLocal.class.isAssignableFrom(clazz));
    assertSame("supertype must be the raw ThreadLocal class",
        ThreadLocal.class, clazz.getSuperclass());
  }

  @Test
  public void instanceFieldIsPublicStaticVolatileAndNonNullAtClassLoad() throws Exception {
    // The shutdown listener can null INSTANCE, but only after engine shutdown — at the
    // start of any unit test the engine is either uninitialised or initialised, never
    // shutdown. Pinning "non-null" + the modifier set catches a refactor that drops
    // volatile (visibility hazard for the listener-driven null-out) or makes it final
    // (which would break the listener's onShutdown clear).
    Field f = HookReplacedRecordThreadLocal.class.getDeclaredField("INSTANCE");
    int mods = f.getModifiers();
    assertTrue("INSTANCE must be public", Modifier.isPublic(mods));
    assertTrue("INSTANCE must be static", Modifier.isStatic(mods));
    assertTrue("INSTANCE must be volatile (read by listener-cleared updates)",
        Modifier.isVolatile(mods));
    assertFalse("INSTANCE must NOT be final (the shutdown listener nulls it)",
        Modifier.isFinal(mods));
    assertSame("INSTANCE must be typed as HookReplacedRecordThreadLocal",
        HookReplacedRecordThreadLocal.class, f.getType());
    assertNotNull("INSTANCE must be non-null at class-load time", f.get(null));
  }

  @Test
  public void getIfDefinedReturnsNullWhenNothingSetOnCurrentThread() {
    // Use a fresh instance so we are not influenced by any thread-local state another
    // test on this worker thread may have left under the singleton INSTANCE.
    var local = new HookReplacedRecordThreadLocal();
    assertNull("fresh instance must report no value on this thread", local.getIfDefined());
    assertFalse("fresh instance must report isDefined()==false on this thread",
        local.isDefined());
  }

  @Test
  public void setOnCurrentThreadIsObservableThroughGetIfDefinedAndIsDefined() {
    // Pin the round-trip contract: set(record) → getIfDefined() returns identical ref;
    // isDefined() flips to true; remove() reverses both. The identity check (assertSame)
    // is load-bearing — a refactor that wraps/copies the record before storage would
    // silently break callers reading it back through getIfDefined().
    var local = new HookReplacedRecordThreadLocal();
    var record = Mockito.mock(DBRecord.class);

    local.set(record);
    assertTrue("isDefined() must report true after set", local.isDefined());
    assertSame("getIfDefined() must return the exact stored reference",
        record, local.getIfDefined());

    local.remove();
    assertFalse("isDefined() must report false after remove", local.isDefined());
    assertNull("getIfDefined() must return null after remove", local.getIfDefined());
  }

  @Test
  public void valuesAreIsolatedPerThread() throws Exception {
    // ThreadLocal contract pin: a value set on the current thread MUST NOT leak to a
    // sibling thread. This guards against a future "improvement" that swaps
    // ThreadLocal for a shared map.
    var local = new HookReplacedRecordThreadLocal();
    local.set(Mockito.mock(DBRecord.class));

    var siblingObservation = new boolean[1];
    var sibling = new Thread(() -> siblingObservation[0] = local.isDefined());
    sibling.start();
    sibling.join();

    assertFalse("sibling thread must see isDefined()==false even though current thread set",
        siblingObservation[0]);
  }
}
