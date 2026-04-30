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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseLifecycleListener.PRIORITY;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Shape pin for {@link DatabaseLifecycleListenerAbstract}. PSI all-scope
 * {@code ReferencesSearch} confirms zero references and zero subclasses across the full
 * module graph; the abstract base was originally drafted as a convenience adapter for the
 * {@link DatabaseLifecycleListener} interface, but the interface itself already provides
 * {@code default} no-op bodies for every callback, so the adapter has no functional value.
 *
 * <p>The single observable that distinguishes this adapter from "implements the interface
 * with all defaults" is its {@link #getPriority()} override returning {@link PRIORITY#REGULAR}
 * (the interface default is {@link PRIORITY#LAST}). Pinning this delta is what makes the
 * pin falsifiable — drop the override and the test fails.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this abstract class and this test file
 * together. Any remaining caller (none today) can implement {@code DatabaseLifecycleListener}
 * directly and override {@code getPriority()} to {@code REGULAR} explicitly if that priority
 * is needed.
 *
 * <p>Standalone: the four {@code on*} callbacks have empty bodies and are exercised here
 * via Mockito mocks of {@link DatabaseSessionEmbedded} purely to prove they return
 * normally — no real database session is created.
 */
public class DatabaseLifecycleListenerAbstractDeadCodeTest {

  /** Minimal subclass — no overrides. Uses the abstract class's defaults end-to-end. */
  private static final class NoOpListener extends DatabaseLifecycleListenerAbstract {
  }

  @Test
  public void classIsPublicAbstractAndImplementsLifecycleListener() {
    var clazz = DatabaseLifecycleListenerAbstract.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("must be abstract", Modifier.isAbstract(clazz.getModifiers()));
    assertTrue("must implement DatabaseLifecycleListener",
        DatabaseLifecycleListener.class.isAssignableFrom(clazz));
  }

  @Test
  public void getPriorityOverridesInterfaceDefaultToRegular() {
    // The whole point of this adapter (over implementing the interface directly) is the
    // priority delta: interface default LAST → adapter REGULAR. Pinning this is the
    // single load-bearing observable that distinguishes the adapter from a no-op alias.
    var listener = new NoOpListener();
    assertSame("adapter must override getPriority() to REGULAR (vs interface default LAST)",
        PRIORITY.REGULAR, listener.getPriority());
    assertEquals("interface default must remain LAST so the override stays meaningful",
        PRIORITY.LAST, new DatabaseLifecycleListener() {
        }.getPriority());
  }

  @Test
  public void allFourCallbacksAreNoOpsAndReturnNormally() {
    // Every callback's body is an empty `{}` — calling them with a Mockito mock proves
    // they do not touch the supplied session and never throw. Pinning the no-op contract
    // means a future override that introduces side effects is caught immediately.
    var listener = new NoOpListener();
    var session = Mockito.mock(DatabaseSessionEmbedded.class);

    listener.onCreate(session);
    listener.onOpen(session);
    listener.onClose(session);
    listener.onDrop(session);

    Mockito.verifyNoInteractions(session);
  }
}
