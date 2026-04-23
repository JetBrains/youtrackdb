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
package com.jetbrains.youtrackdb.internal.core.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext.TIMEOUT_STRATEGY;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Standalone tests for the branches of {@link CommandExecutorAbstract} that are not already
 * exercised indirectly through its production callers (Traverse, SQLEngine,
 * SQLFunctionPathFinder, SQLFunctionShortestPath).
 *
 * <p>The {@link CommandExecutorAbstract#checkInterruption(CommandContext)} static helper has two
 * truly distinct branches:
 *
 * <ul>
 *   <li>{@code ExecutionThreadLocal.isInterruptCurrentOperation()} → throws
 *       {@link com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException}.
 *       Reachable only from a {@code SoftThread} that has been flagged for shutdown — the
 *       infrastructure for that is shared across server threads, and exercising it standalone
 *       would require spinning a {@code SoftThread} subclass. Deferred to the Step 3 Traverse
 *       tests, which drive this via real cluster iteration.
 *   <li>{@code iContext == null || iContext.checkTimeout()} → the return branch. Here we cover
 *       both the null-context and the context-with-no-timeout paths standalone, plus the
 *       {@code RETURN}-strategy timeout path that returns {@code false}.
 * </ul>
 *
 * <p>The trivial getters/setters are covered here instead of at the call site so they count as
 * dedicated coverage rather than incidental.
 */
public class CommandExecutorAbstractTest {

  /**
   * Minimal concrete {@link CommandExecutorAbstract} subclass. All overridable behavior defaults
   * to sensible no-ops; tests focus on the inherited superclass surface.
   */
  private static final class StubExecutor extends CommandExecutorAbstract {
    @Override
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    public <RET extends CommandExecutor> RET parse(DatabaseSessionEmbedded session,
        CommandRequest iRequest) {
      return (RET) this;
    }

    @Override
    public Object execute(DatabaseSessionEmbedded session, Map<Object, Object> iArgs) {
      return null;
    }

    @Override
    public boolean isIdempotent() {
      return true;
    }

    @Override
    public String getSyntax() {
      return "stub";
    }

    @Override
    protected void throwSyntaxErrorException(String dbName, String iText) {
      throw new UnsupportedOperationException("stub does not parse: " + iText);
    }

    /** Exposes the protected instance-level {@code checkInterruption} for direct testing. */
    boolean invokeInstanceCheckInterruption() {
      return checkInterruption();
    }
  }

  // ---------------------------------------------------------------------------
  // checkInterruption(CommandContext) — return-branch variants.
  // Source: CommandExecutorAbstract.java:117-124.
  // ---------------------------------------------------------------------------

  /**
   * On a normal (non-SoftThread) thread the interrupt flag is always {@code false}, so
   * {@code checkInterruption(null)} falls through to the {@code iContext == null} short-circuit
   * and returns {@code true}.
   */
  @Test
  public void checkInterruptionStaticNullContextReturnsTrue() {
    assertTrue("null context short-circuits to true on a non-interrupted thread",
        CommandExecutorAbstract.checkInterruption(null));
  }

  /**
   * When the context has no timeout configured, {@code BasicCommandContext.checkTimeout()}
   * traverses to the parent (null) and returns {@code true}. Pin this happy-path return from
   * the static helper.
   */
  @Test
  public void checkInterruptionStaticContextWithoutTimeoutReturnsTrue() {
    var ctx = new BasicCommandContext();
    // No beginExecution call → timeoutMs stays 0 → checkTimeout skips the body entirely.
    assertTrue(CommandExecutorAbstract.checkInterruption(ctx));
  }

  /**
   * When the context's timeout has elapsed and the strategy is {@code RETURN},
   * {@code checkTimeout()} returns {@code false}, so the static helper returns {@code false}.
   * This pins the false-return path for callers that use it as a loop-continue signal (e.g.,
   * {@code SQLFunctionShortestPath}).
   */
  @Test
  public void checkInterruptionStaticExpiredReturnStrategyReturnsFalse()
      throws InterruptedException {
    var ctx = new BasicCommandContext();
    // 1 ms timeout + RETURN — guarantees the next checkTimeout sees it as elapsed.
    ctx.beginExecution(1L, TIMEOUT_STRATEGY.RETURN);
    Thread.sleep(3);

    assertFalse("expired RETURN-strategy context must return false",
        CommandExecutorAbstract.checkInterruption(ctx));
  }

  /**
   * When the context's timeout has elapsed and the strategy is {@code EXCEPTION},
   * {@code checkTimeout()} throws {@link TimeoutException}. Pin the throw so callers can rely
   * on the behavior difference between RETURN and EXCEPTION strategies.
   */
  @Test
  public void checkInterruptionStaticExpiredExceptionStrategyThrows() throws InterruptedException {
    var ctx = new BasicCommandContext();
    ctx.beginExecution(1L, TIMEOUT_STRATEGY.EXCEPTION);
    Thread.sleep(3);

    var ex = org.junit.Assert.assertThrows(TimeoutException.class,
        () -> CommandExecutorAbstract.checkInterruption(ctx));
    assertTrue("message must name the timeout: " + ex.getMessage(),
        ex.getMessage().contains("timeout"));
  }

  /**
   * The instance-level {@code checkInterruption()} forwards to the static one with
   * {@code this.context}. On a fresh executor with a plain context, it must return {@code true}.
   */
  @Test
  public void checkInterruptionInstanceForwardsToStaticWithThisContext() {
    var exec = new StubExecutor();
    exec.setContext(new BasicCommandContext());

    assertTrue("instance checkInterruption with a no-timeout context must return true",
        exec.invokeInstanceCheckInterruption());
  }

  // ---------------------------------------------------------------------------
  // Getters, setters, defaults on CommandExecutorAbstract's public surface.
  // ---------------------------------------------------------------------------

  /**
   * After default construction, {@code limit} is {@code -1}, {@code parameters} is {@code null},
   * {@code fetchPlan} is {@code null}, {@code progressListener} is {@code null}, and
   * {@code isCacheable()} returns {@code false}. Pin the declared defaults.
   */
  @Test
  public void defaultsMatchDeclaredInitializers() {
    var exec = new StubExecutor();

    assertEquals(-1, exec.getLimit());
    assertNull(exec.getParameters());
    assertNull(exec.getFetchPlan());
    assertNull(exec.getProgressListener());
    assertFalse("default isCacheable is false", exec.isCacheable());
    assertEquals("default security op is READ",
        Role.PERMISSION_READ, exec.getSecurityOperationType());
  }

  /**
   * {@code setLimit} and {@code setProgressListener} both return {@code this} for chaining — pin
   * both in one test so the TYPE-parameter quirk on the super-class signature is exercised.
   */
  @Test
  public void settersReturnThisForChaining() {
    var exec = new StubExecutor();
    CommandExecutor afterLimit = exec.setLimit(7);
    ProgressListener listener = new ProgressListener() {
      @Override
      public void onBegin(Object iTask, long iTotal, Object iMetadata) {
      }

      @Override
      public boolean onProgress(Object iTask, long iCounter, float iPercent) {
        return true;
      }

      @Override
      public void onCompletition(DatabaseSessionEmbedded session, Object iTask,
          boolean iSucceed) {
      }
    };
    CommandExecutor afterListener = exec.setProgressListener(listener);

    assertSame("setLimit returns this", exec, afterLimit);
    assertSame("setProgressListener returns this", exec, afterListener);
    assertEquals(7, exec.getLimit());
    assertSame(listener, exec.getProgressListener());
  }

  /**
   * {@code setContext} assigns the caller's context; {@code getContext} returns it. Pin that no
   * lazy-init happens on the executor side (unlike {@code CommandRequestAbstract.getContext}).
   */
  @Test
  public void contextSetterAndGetter() {
    var exec = new StubExecutor();

    assertNull("context is null before set on CommandExecutorAbstract", exec.getContext());

    var ctx = new BasicCommandContext();
    exec.setContext(ctx);
    assertSame("getContext returns the assigned context", ctx, exec.getContext());
  }

  /**
   * {@code getInvolvedCollections} defaults to the unmodifiable empty set (via
   * {@link Collections#EMPTY_SET}). Pin both the content and the reference shape: callers rely
   * on a non-null return.
   */
  @Test
  public void getInvolvedCollectionsDefaultsToEmpty() {
    var exec = new StubExecutor();

    Set<String> involved = exec.getInvolvedCollections(null);

    assertTrue("default involved-collections set is empty", involved.isEmpty());
  }

  /**
   * {@code toString} includes both the simple class name of the concrete subclass and the
   * {@code parserText} field from the parent {@link
   * com.jetbrains.youtrackdb.internal.common.parser.BaseParser}. On a freshly-constructed
   * executor without a call to {@code init()}, parserText is null — so the toString form is
   * {@code "StubExecutor [text=null]"}. Pin the exact shape.
   */
  @Test
  public void toStringUsesClassNameAndParserText() {
    var exec = new StubExecutor();

    assertEquals("StubExecutor [text=null]", exec.toString());
  }

  /**
   * Minimal concrete-subclass-local dummy that returns a single-element list from execute. Used
   * only to satisfy {@link CommandExecutorAbstract}'s abstract surface — tests do not call it.
   */
  @SuppressWarnings("unused")
  private static List<Object> unusedSentinel() {
    return List.of();
  }
}
