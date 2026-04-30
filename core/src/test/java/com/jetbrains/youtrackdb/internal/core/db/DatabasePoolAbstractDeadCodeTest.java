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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.concur.lock.AdaptiveLock;
import com.jetbrains.youtrackdb.internal.common.concur.resource.ResourcePoolListener;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBListener;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link DatabasePoolAbstract}. PSI all-scope {@code ReferencesSearch}
 * confirms the only production-side reference is from {@link DatabasePoolBase} (which
 * itself has zero callers anywhere — see {@link DatabasePoolBaseDeadCodeTest}). The only
 * remaining reachers are tests: {@code DatabasePoolAbstractEvictionTest$TestPool} (an
 * eviction-focused subclass) and the anonymous subclass inside {@link DatabasePoolBase}
 * itself. The class is therefore production-dead but test-reachable; deletion in the
 * deferred-cleanup track must drop {@link DatabasePoolBase} and either delete or relocate
 * {@link DatabasePoolAbstract}'s eviction logic to {@link DatabasePoolImpl} in lockstep.
 *
 * <p>Reflection-only on purpose: the constructors register a {@link YouTrackDBListener}
 * with the global {@code YouTrackDBEnginesManager} and schedule a recurring evictor on
 * the engine-wide scheduled pool. Instantiating the class from a unit test would dirty
 * engines-manager state for every parallel test class. Pinning the public surface, the
 * constructor signatures, and the inherited type vector via {@link Class}-level reflection
 * catches deletion / rename / signature drift without touching engines-manager state.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this abstract class together with this
 * test file once {@link DatabasePoolBase} is deleted. The eviction logic in this base
 * (the inner {@code Evictor} class) is duplicated by {@link DatabasePoolImpl}'s eviction
 * path; the deferred-cleanup track may either (a) consolidate both into one impl, or
 * (b) drop this base entirely if the eviction-focused test no longer needs a separate
 * implementation hierarchy.
 */
public class DatabasePoolAbstractDeadCodeTest {

  // Public surface declared on this class (excluding inherited and synthetic). Pinning
  // the set guards against silent rename / drop / addition.
  private static final Set<String> EXPECTED_PUBLIC_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "acquire",
      "getMaxConnections",
      "getCreatedInstances",
      "getAvailableConnections",
      "getConnectionsInCurrentThread",
      "release",
      "getPools",
      "close",
      "remove",
      "getMaxSize",
      "onStorageRegistered",
      "onStorageUnregistered",
      "onShutdown"));

  @Test
  public void classIsPublicAbstractAndExtendsAdaptiveLock() {
    var clazz = DatabasePoolAbstract.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("must be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertSame("must extend AdaptiveLock — locking semantics depend on this base",
        AdaptiveLock.class, clazz.getSuperclass());
  }

  @Test
  public void implementsResourcePoolListenerAndYouTrackDBListener() {
    // Pin the exact super-interface set so a refactor that drops or adds an interface is
    // caught. ResourcePoolListener is the contract that bridges to the underlying
    // ReentrantResourcePool; YouTrackDBListener is the engines-manager registration entry
    // (the static onShutdown listener is what makes the eviction scheduling rely on
    // engine lifecycle).
    var supers = DatabasePoolAbstract.class.getInterfaces();
    var asSet = new TreeSet<String>();
    for (Class<?> i : supers) {
      asSet.add(i.getName());
    }
    assertEquals(
        "implements set must remain {ResourcePoolListener, YouTrackDBListener}",
        new TreeSet<>(Set.of(
            ResourcePoolListener.class.getName(),
            YouTrackDBListener.class.getName())),
        asSet);
  }

  @Test
  public void declaresExactlyTwoPublicConstructorsForOptionalTimeoutOverride() {
    var ctors = DatabasePoolAbstract.class.getDeclaredConstructors();
    assertEquals(
        "must declare exactly two ctors — 5-arg (default timeout) + 6-arg (explicit timeout)",
        2, ctors.length);

    boolean fiveArg = false;
    boolean sixArg = false;
    for (Constructor<?> c : ctors) {
      assertTrue("ctors must be public — subclasses (DatabasePoolBase, TestPool) need them",
          Modifier.isPublic(c.getModifiers()));
      switch (c.getParameterCount()) {
        case 5 -> {
          fiveArg = true;
          // (Object owner, int minSize, int maxSize, long idleTimeout, long evictRunsMillis)
          assertArrayEquals(
              "5-arg ctor signature must remain (Object,int,int,long,long)",
              new Class<?>[] {Object.class, int.class, int.class, long.class, long.class},
              c.getParameterTypes());
        }
        case 6 -> {
          sixArg = true;
          // (Object owner, int min, int max, int timeout, long idleTimeout, long evictRunsMillis)
          assertArrayEquals(
              "6-arg ctor signature must remain (Object,int,int,int,long,long)",
              new Class<?>[] {
                  Object.class, int.class, int.class, int.class, long.class, long.class},
              c.getParameterTypes());
        }
        default -> org.junit.Assert.fail(
            "unexpected ctor arity " + c.getParameterCount() + " — pin needs review");
      }
    }
    assertTrue("must declare a 5-arg ctor", fiveArg);
    assertTrue("must declare a 6-arg ctor", sixArg);
  }

  @Test
  public void publicDeclaredSurfaceMatchesPinnedSet() {
    // Capture only public, non-synthetic, declared methods (not inherited from
    // AdaptiveLock or interface defaults).
    var actual = new TreeSet<String>();
    for (Method m : DatabasePoolAbstract.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      if (!Modifier.isPublic(m.getModifiers())) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("public declared method-name set must match the pinned surface",
        EXPECTED_PUBLIC_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void declaresAtLeastOneInnerEvictorClassForBackgroundEviction() {
    // The Evictor inner class is the eviction implementation the constructor schedules on
    // the engine-wide scheduled pool. If a future refactor inlines the evictor into a
    // lambda or moves it to a sibling top-level class, this pin fails — signalling that
    // the eviction-scheduling assumption needs to be re-checked against
    // DatabasePoolAbstractEvictionTest before deletion lands.
    boolean foundEvictor = false;
    for (Class<?> inner : DatabasePoolAbstract.class.getDeclaredClasses()) {
      if ("Evictor".equals(inner.getSimpleName())) {
        foundEvictor = true;
        assertTrue(
            "Evictor must implement Runnable — it is scheduled via ScheduledExecutorService",
            Runnable.class.isAssignableFrom(inner));
        break;
      }
    }
    assertTrue("DatabasePoolAbstract.Evictor inner class must be present",
        foundEvictor);
  }

  @Test
  public void onShutdownDelegatesToCloseViaContractMethod() throws Exception {
    // Pin the YouTrackDBListener.onShutdown override exists with a void return — the
    // engines-manager calls this on shutdown and we must not silently lose the call (the
    // body delegates to close()). A signature drift (e.g. accepting a parameter) would
    // break the listener contract.
    Method onShutdown = DatabasePoolAbstract.class.getDeclaredMethod("onShutdown");
    assertSame("onShutdown must return void", void.class, onShutdown.getReturnType());
    assertTrue("onShutdown must be public", Modifier.isPublic(onShutdown.getModifiers()));
  }
}
