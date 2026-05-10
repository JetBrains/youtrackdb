/*
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
package com.jetbrains.youtrackdb.internal.core.servlet;

import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import javax.servlet.ServletContextEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Pinned because {@link ServletContextLifeCycleListener} is {@code @WebListener}-annotated and
 * reachable via Servlet 3.0+ container annotation scanning. The class has zero PSI references
 * inside the project but MUST NOT be removed by dead-code sweeps — Servlet containers
 * instantiate it via classpath scanning, not by source-level reference. See finding A4b in the
 * Track 22a cluster classification table; the cluster is marked {@code 22a-coverage-only}.
 *
 * <p>The test covers only the no-op false-branch — when the {@code
 * INIT_IN_SERVLET_CONTEXT_LISTENER} flag is {@code false}, both lifecycle methods become
 * no-ops because the bodies are guarded by {@code if (… .getValueAsBoolean())}. The
 * true-branch ({@code contextInitialized} drives {@code YouTrackDBEnginesManager.startUp(true)}
 * and {@code contextDestroyed} drives {@code shutdown()}) requires a Servlet container fixture
 * and engine-state setup; that branch is recorded as a D4 residual under Constraint 7.
 *
 * <p>The test mutates {@code GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER} (process-
 * wide state) and therefore carries {@code @Category(SequentialTest)} per finding T5/R8 with
 * snapshot/restore of the original config value.
 */
@Category(SequentialTest.class)
public class ServletContextLifeCycleListenerTest {

  private Object snapInitInServletContextListener;
  private boolean snapInitInServletContextListenerChanged;

  @Before
  public void snapshotInitInServletContextListener() {
    snapInitInServletContextListener =
        GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.getValue();
    snapInitInServletContextListenerChanged =
        GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.isChanged();
    // Flip to false for the no-op branch coverage.
    GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.setValue(false);
  }

  @After
  public void restoreInitInServletContextListener() {
    if (snapInitInServletContextListenerChanged) {
      GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER
          .setValue(snapInitInServletContextListener);
    } else {
      GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.setValue(
          GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.getDefValue());
    }
  }

  /**
   * When {@code INIT_IN_SERVLET_CONTEXT_LISTENER=false}, {@link
   * ServletContextLifeCycleListener#contextInitialized(ServletContextEvent)} silently returns
   * — no engine startup, no log entry, no NPE on the (deliberately) null-passed
   * {@link ServletContextEvent}. Pinning the false-branch as a no-op guards against a
   * regression that drops the guard or inverts the boolean.
   */
  @Test
  public void contextInitializedIsNoOpWhenFlagIsFalse() {
    var listener = new ServletContextLifeCycleListener();
    // Capture the engines-manager singleton before the call. The guarded contextInitialized must
    // not call YouTrackDBEnginesManager.startUp(true) when the flag is false; pinning the same
    // instance reference before and after (using identity, including null==null) catches a
    // regression that drops the guard or inverts the boolean — startUp(true) installs a NEW
    // singleton, so the post-call reference would differ.
    YouTrackDBEnginesManager before = YouTrackDBEnginesManager.instance();
    // The guard short-circuits before any reference to the event arg, so passing null is safe.
    listener.contextInitialized(null);
    YouTrackDBEnginesManager after = YouTrackDBEnginesManager.instance();
    assertSame(
        "no-op false-branch must not allocate a new YouTrackDBEnginesManager singleton",
        before,
        after);
  }

  /**
   * When {@code INIT_IN_SERVLET_CONTEXT_LISTENER=false}, {@link
   * ServletContextLifeCycleListener#contextDestroyed(ServletContextEvent)} silently returns —
   * no engine shutdown, no log entry, no NPE on the null event arg. Pinning the false-branch
   * as a no-op.
   */
  @Test
  public void contextDestroyedIsNoOpWhenFlagIsFalse() {
    var listener = new ServletContextLifeCycleListener();
    // Capture the engines-manager singleton before the call. With the flag false, contextDestroyed
    // must NOT touch the engines manager (it would otherwise call shutdown() and null-out the
    // singleton). Pinning identity equality across the call catches a regression that drops the
    // guard.
    YouTrackDBEnginesManager before = YouTrackDBEnginesManager.instance();
    listener.contextDestroyed(null);
    YouTrackDBEnginesManager after = YouTrackDBEnginesManager.instance();
    assertSame(
        "no-op false-branch must not shut down or replace the YouTrackDBEnginesManager singleton",
        before,
        after);
  }
}
