/*
 *
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

import com.jetbrains.youtrackdb.internal.core.query.BasicLiveQueryResultListener;
import com.jetbrains.youtrackdb.internal.core.query.LiveQueryResultListener;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Shape pin for {@link LiveQueryBatchResultListener}. PSI all-scope {@code ReferencesSearch}
 * confirms the interface has zero references across the full module graph (core / server /
 * driver / embedded / gremlin-annotations / tests / docker-tests). It was drafted to allow
 * batched live-query notifications via {@code onBatchEnd}, but no production code or test
 * code ever called the entry point that would dispatch it; the live-query pipeline only
 * understands the parent {@link LiveQueryResultListener}.
 *
 * <p>This pin locks the public surface so a future rename, a silent return-type change, or
 * an accidental method addition fails loudly. Combined with the WHEN-FIXED marker it ensures
 * the deletion is a deliberate change rather than a silent edit that breaks an unknown
 * external consumer reaching for the type by reflection.
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this interface together with this test
 * file once the live-query subsystem dead-code absorption block lands. No production
 * callers exist and the parent {@link LiveQueryResultListener} surface is sufficient for
 * every remaining live-query consumer.
 *
 * <p>Standalone: no database session, no parallel-test risk.
 */
public class LiveQueryBatchResultListenerDeadCodeTest {

  @Test
  public void typeIsPublicInterface() {
    var clazz = LiveQueryBatchResultListener.class;
    assertTrue("must be an interface", clazz.isInterface());
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
  }

  @Test
  public void declaresExactlyOneMethodNamedOnBatchEnd() {
    // The interface's reason for existing is the onBatchEnd hook layered on top of the
    // parent live-query listener. Pin "exactly one declared method" so adding another
    // method (or renaming this one) is caught immediately — both are signals that the
    // dead-code reframe is being undone and the deletion needs to be re-evaluated.
    var declared = LiveQueryBatchResultListener.class.getDeclaredMethods();
    assertEquals("must declare exactly one method on top of the parent listener",
        1, declared.length);

    Method onBatchEnd = declared[0];
    assertEquals("declared method must be named onBatchEnd",
        "onBatchEnd", onBatchEnd.getName());
    assertSame("onBatchEnd must return void",
        void.class, onBatchEnd.getReturnType());
    assertArrayEquals(
        "onBatchEnd must take exactly one DatabaseSessionEmbedded parameter",
        new Class<?>[] {DatabaseSessionEmbedded.class},
        onBatchEnd.getParameterTypes());
    // Interface methods are public abstract by default; pin both via Modifier so a future
    // edit that adds a default body or downgrades visibility is caught.
    assertTrue("onBatchEnd must be public", Modifier.isPublic(onBatchEnd.getModifiers()));
    assertTrue("onBatchEnd must be abstract", Modifier.isAbstract(onBatchEnd.getModifiers()));
  }

  @Test
  public void extendsLiveQueryResultListenerOnlyAndInheritsBasicSurface() {
    // The single super-interface is LiveQueryResultListener; the basic-listener surface
    // (onCreate/onUpdate/onDelete/onError/onEnd) is inherited via that chain. Pin the
    // exact super list so adding a sibling parent (e.g. an unrelated marker interface)
    // is caught as a structural drift.
    var supers = LiveQueryBatchResultListener.class.getInterfaces();
    assertArrayEquals("must extend exactly LiveQueryResultListener",
        new Class<?>[] {LiveQueryResultListener.class}, supers);
    assertTrue("must transitively be assignable to BasicLiveQueryResultListener",
        BasicLiveQueryResultListener.class.isAssignableFrom(
            LiveQueryBatchResultListener.class));
  }
}
