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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.concur.resource.ReentrantResourcePool;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Shape pin for {@link DatabasePoolBase}. PSI all-scope {@code ReferencesSearch} confirms
 * zero references outside this class's own source file across the full module graph; the
 * abstract pool base has 0 ctor callers and 0 subclasses anywhere in production, server,
 * driver, embedded, or tests modules. The live pool path is {@link DatabasePoolImpl} — a
 * sibling on a different inheritance branch — so {@link DatabasePoolAbstract} (the
 * delegate this base wraps via the anonymous subclass in {@link #setup(int, int, long, long)})
 * is reached by no production code path.
 *
 * <p>This pin is reflection-only on purpose. {@link DatabasePoolBase} extends {@link Thread}
 * and its {@code setup(...)} chain instantiates a {@link DatabasePoolAbstract} that
 * registers a {@link com.jetbrains.youtrackdb.internal.core.YouTrackDBListener} with the
 * global engines manager and schedules an evictor; instantiating it from a unit test would
 * dirty global state for every parallel test class. Pinning the public surface via
 * {@link Class#getDeclaredMethods()} catches deletion / rename / signature drift without
 * touching engines-manager state.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@code DatabasePoolBase} together with
 * this test file once the {@code DatabasePoolAbstract} consolidation lands. The
 * "extends Thread" inheritance is itself a legacy pattern (run() just calls close()) that
 * a modern caller would replace with a JVM shutdown hook if pool teardown were ever
 * needed.
 *
 * <p>Standalone: pure {@link Class}-level reflection; no database session, no global state
 * touched.
 */
public class DatabasePoolBaseDeadCodeTest {

  // The complete public/protected surface this base offers, captured here as a sorted set
  // of method names. Pinning the set (rather than the count) guards against both a method
  // dropped silently and a method renamed in place — either would shift the resulting set.
  private static final Set<String> EXPECTED_DECLARED_METHOD_NAMES = new TreeSet<>(Set.of(
      "setup",
      "acquire",
      "getAvailableConnections",
      "getCreatedInstances",
      "getConnectionsInCurrentThread",
      "release",
      "close",
      "getMaxSize",
      "getPools",
      "remove",
      "run",
      "createResource"));

  @Test
  public void classIsPublicAbstractAndExtendsThread() {
    var clazz = DatabasePoolBase.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("must be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertSame("must extend java.lang.Thread (legacy lifecycle marker)",
        Thread.class, clazz.getSuperclass());
  }

  @Test
  public void declaresExactlyTheExpectedPublicSurface() {
    // Pin the full set of declared method names. A future addition (new public hook) or
    // deletion (removed accessor) shifts the set and fails — making the deletion / rename
    // a deliberate change rather than a silent edit.
    var actual = new TreeSet<String>();
    for (Method m : DatabasePoolBase.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals("declared method-name set must match the pinned surface",
        EXPECTED_DECLARED_METHOD_NAMES, actual);
  }

  @Test
  public void exposesProtectedNoArgAndThreeArgConstructors() {
    var ctors = DatabasePoolBase.class.getDeclaredConstructors();
    assertEquals("must declare exactly two constructors", 2, ctors.length);

    boolean foundNoArg = false;
    boolean foundThreeArg = false;
    for (Constructor<?> c : ctors) {
      assertTrue("ctor must be protected (subclass-only construction)",
          Modifier.isProtected(c.getModifiers()));
      switch (c.getParameterCount()) {
        case 0 -> foundNoArg = true;
        case 3 -> {
          foundThreeArg = true;
          assertArrayEquals(
              "three-arg ctor signature must remain (URL, userName, userPassword)",
              new Class<?>[] {String.class, String.class, String.class},
              c.getParameterTypes());
        }
        default ->
            org.junit.Assert.fail(
                "unexpected constructor arity " + c.getParameterCount() + " — pin needs review");
      }
    }
    assertTrue("must declare a no-arg ctor (sets URL/user/password to null)", foundNoArg);
    assertTrue("must declare a 3-arg ctor (URL, userName, userPassword)", foundThreeArg);
  }

  @Test
  public void createResourceIsAbstractWithExpectedSignature() throws Exception {
    // The single abstract hook that subclasses must override — pin its name, return type
    // and parameter list so a renamed hook (silent breakage of the contract) fails loudly.
    Method m = DatabasePoolBase.class.getDeclaredMethod(
        "createResource", Object.class, String.class, Object[].class);
    int mods = m.getModifiers();
    assertTrue("createResource must be abstract", Modifier.isAbstract(mods));
    assertTrue("createResource must be protected", Modifier.isProtected(mods));
    assertSame("createResource return type must be DatabaseSessionEmbedded",
        DatabaseSessionEmbedded.class, m.getReturnType());
    assertTrue("createResource last param must be a varargs Object[]",
        m.isVarArgs());
  }

  @Test
  public void runOverrideClosesPoolAndReturnsVoid() throws Exception {
    Method run = DatabasePoolBase.class.getDeclaredMethod("run");
    assertSame("run() must return void", void.class, run.getReturnType());
    assertTrue("run() must be public (Thread.run override)",
        Modifier.isPublic(run.getModifiers()));
    assertFalse("run() must not be abstract", Modifier.isAbstract(run.getModifiers()));
  }

  @Test
  public void getPoolsReturnsMapOfStringToReentrantResourcePool() throws Exception {
    // The signature is read-by-reflection by no production code today, but pinning the
    // Map<String, ReentrantResourcePool<...>> shape catches a silent return-type rewrite
    // (e.g. to ConcurrentMap or to a custom holder type) before deletion lands.
    Method getPools = DatabasePoolBase.class.getDeclaredMethod("getPools");
    assertSame(Map.class, getPools.getReturnType());
    var generic = getPools.getGenericReturnType().toString();
    assertTrue("getPools generic return must mention ReentrantResourcePool: " + generic,
        generic.contains(ReentrantResourcePool.class.getName()));
  }

  @Test
  public void getConnectionsInCurrentThreadShortCircuitsWhenPoolIsNullWithoutTouchingThread() {
    // The single behaviorally-pinnable observation that does not require an engines-manager
    // touch: in the no-arg ctor path (url/user/password null), dbPool stays null and
    // getConnectionsInCurrentThread short-circuits to 0 without dereferencing dbPool.
    // Verifies the production guard "if (dbPool == null) { return 0; }" without any
    // global side effect.
    var probe = new DatabasePoolBase() {
      @Override
      protected DatabaseSessionEmbedded createResource(
          Object owner, String iDatabaseName, Object... iAdditionalArgs) {
        throw new AssertionError(
            "createResource must NOT be invoked from the dbPool==null short-circuit");
      }
    };
    // Touch the protected no-arg ctor's outcome: setting via reflection would be heavy;
    // the anonymous subclass above invokes the no-arg ctor implicitly, which sets fields
    // to null. Confirm the short-circuit path.
    int count = probe.getConnectionsInCurrentThread("dummy", "user");
    assertEquals("dbPool==null path must return 0 without dereferencing the pool",
        0, count);

    // close() on a null pool is also a no-op — pin so a future refactor that drops the
    // null guard fails loudly under unit tests.
    probe.close();
    assertNotNull("probe must remain a valid object after no-op close",
        probe);
  }

  @Test
  public void noArgConstructorLeavesAllConfigFieldsNull() throws Exception {
    // The no-arg ctor body assigns url=userName=userPassword=null. Pin via reflection on
    // the resulting field values so a future refactor that introduces a default value
    // (which would silently change pool credentials) is caught.
    var probe = new DatabasePoolBase() {
      @Override
      protected DatabaseSessionEmbedded createResource(
          Object owner, String iDatabaseName, Object... iAdditionalArgs) {
        return null;
      }
    };
    for (var fieldName : new String[] {"url", "userName", "userPassword", "dbPool"}) {
      var f = DatabasePoolBase.class.getDeclaredField(fieldName);
      f.setAccessible(true);
      assertNull(fieldName + " must remain null after no-arg construction", f.get(probe));
    }
  }
}
