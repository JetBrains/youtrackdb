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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext.TIMEOUT_STRATEGY;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Standalone unit tests for {@link CommandRequestAbstract}. The only production subclass is
 * {@link com.jetbrains.youtrackdb.internal.core.command.script.CommandScript}, which is dead code
 * pinned for Track 22 deletion (see {@code command/script/CommandScriptDeadCodeTest}). So this
 * test uses a minimal {@link StubCommandRequest} to exercise the inherited getters, setters, and
 * the protected {@code setParameters} / {@code convertToParameters} branches without depending on
 * any live executor wiring.
 */
public class CommandRequestAbstractTest {

  // ---------------------------------------------------------------------------
  // Minimal concrete subclass — no execute() behavior needed, because the tests
  // focus on the abstract superclass surface. setParameters is protected, so the
  // stub exposes it via a public thin wrapper for direct exercising.
  // ---------------------------------------------------------------------------

  /**
   * Minimal {@link CommandRequestAbstract} used only to exercise the abstract class's public
   * surface. execute is a no-op returning an empty list because no test calls it; idempotence is
   * fixed to {@code true} to keep the stub predictable.
   */
  static final class StubCommandRequest extends CommandRequestAbstract {

    @Override
    public List<EntityImpl> execute(@Nonnull DatabaseSessionEmbedded querySession,
        Object... iArgs) {
      return List.of();
    }

    @Override
    public boolean isIdempotent() {
      return true;
    }

    /**
     * Thin package-visible wrapper — tests exercise the protected {@code setParameters} without
     * relying on reflection.
     */
    void exposeSetParameters(Object... args) {
      setParameters(args);
    }
  }

  // ---------------------------------------------------------------------------
  // Constructor defaults
  // Source: CommandRequestAbstract.java:40-51.
  // ---------------------------------------------------------------------------

  /**
   * After construction, all inherited fields default to the values declared in
   * {@link CommandRequestAbstract}: {@code limit = -1}, {@code timeoutStrategy = EXCEPTION},
   * {@code useCache = false}, {@code cacheableResult = false}, {@code recordResultSet = true},
   * and the {@code timeoutMs} reads from the {@link GlobalConfiguration#COMMAND_TIMEOUT}
   * global. Listeners and context start as {@code null}.
   */
  @Test
  public void constructorDefaultsMatchDeclaredInitializers() {
    var req = new StubCommandRequest();

    assertEquals(-1, req.getLimit());
    assertEquals(TIMEOUT_STRATEGY.EXCEPTION, req.getTimeoutStrategy());
    // COMMAND_TIMEOUT is a GlobalConfiguration value — read it at test time so this pin survives
    // legitimate tuning of the default.
    assertEquals(
        GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong(), req.getTimeoutTime());
    assertFalse("useCache default is false", req.isUseCache());
    assertFalse("cacheableResult default is false", req.isCacheableResult());
    assertTrue("recordResultSet default is true", req.isRecordResultSet());
    assertNull("no result listener by default", req.getResultListener());
    assertNull("no progress listener by default", req.getProgressListener());
    assertNull("no fetch plan by default", req.getFetchPlan());
    assertNull("no parameters by default", req.getParameters());
    assertTrue("idempotent per the stub", req.isIdempotent());
  }

  // ---------------------------------------------------------------------------
  // Simple getter/setter round-trips
  // ---------------------------------------------------------------------------

  @Test
  public void limitRoundTrip() {
    var req = new StubCommandRequest();
    var returned = req.setLimit(25);
    assertSame("setLimit must return this for chaining", req, returned);
    assertEquals(25, req.getLimit());
  }

  @Test
  public void fetchPlanRoundTrip() {
    var req = new StubCommandRequest();
    CommandRequest returned = req.setFetchPlan("children:-1 *:0");
    assertSame("setFetchPlan must return this for chaining", req, returned);
    assertEquals("children:-1 *:0", req.getFetchPlan());
  }

  @Test
  public void useCacheRoundTrip() {
    var req = new StubCommandRequest();
    req.setUseCache(true);
    assertTrue(req.isUseCache());
  }

  @Test
  public void cacheableResultRoundTrip() {
    var req = new StubCommandRequest();
    req.setCacheableResult(true);
    assertTrue(req.isCacheableResult());
  }

  @Test
  public void recordResultSetRoundTrip() {
    var req = new StubCommandRequest();
    req.setRecordResultSet(false);
    assertFalse("recordResultSet must be overridable to false", req.isRecordResultSet());
  }

  @Test
  public void timeoutRoundTripStoresBothMsAndStrategy() {
    var req = new StubCommandRequest();
    req.setTimeout(1234L, TIMEOUT_STRATEGY.RETURN);
    assertEquals(1234L, req.getTimeoutTime());
    assertEquals(TIMEOUT_STRATEGY.RETURN, req.getTimeoutStrategy());
  }

  @Test
  public void resultListenerRoundTrip() {
    var req = new StubCommandRequest();
    CommandResultListener listener = new CommandResultListener() {
      @Override
      public boolean result(@Nonnull DatabaseSessionEmbedded session, Object iRecord) {
        return true;
      }

      @Override
      public void end(@Nonnull DatabaseSessionEmbedded session) {
      }

      @Override
      public Object getResult() {
        return null;
      }
    };
    req.setResultListener(listener);
    assertSame(listener, req.getResultListener());
  }

  @Test
  public void progressListenerRoundTripReturnsThis() {
    var req = new StubCommandRequest();
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
    var returned = req.setProgressListener(listener);
    assertSame("setProgressListener must return this for chaining", req, returned);
    assertSame(listener, req.getProgressListener());
  }

  // ---------------------------------------------------------------------------
  // getContext() lazy-init contract
  // Source: CommandRequestAbstract.java:166-171.
  // ---------------------------------------------------------------------------

  /**
   * First call to {@code getContext} on a default-constructed request must allocate a new
   * {@link BasicCommandContext} lazily; subsequent calls must return the same instance. This pins
   * the lazy-init branch at line 167-169.
   */
  @Test
  public void getContextLazilyAllocatesAndMemoizes() {
    var req = new StubCommandRequest();

    var ctx = req.getContext();
    assertNotNull("context must be created lazily", ctx);
    assertTrue("lazy-init must produce a BasicCommandContext",
        ctx instanceof BasicCommandContext);
    assertSame("same instance returned on second call", ctx, req.getContext());
  }

  /**
   * {@code setContext} overrides the stored context; subsequent {@code getContext} returns the
   * provided instance rather than the lazy default. A {@code null} set then wipes the context so
   * the next {@code getContext} allocates a fresh one.
   */
  @Test
  public void setContextOverridesAndNullResetsForLazyReinit() {
    var req = new StubCommandRequest();
    var provided = new BasicCommandContext();

    var returnedFromSet = req.setContext(provided);
    assertSame("setContext returns this", req, returnedFromSet);
    assertSame("getContext returns the explicitly-set instance", provided, req.getContext());

    req.setContext(null);
    var reallocated = req.getContext();
    assertNotNull("getContext re-allocates a new context after setContext(null)",
        reallocated);
    // The comparison is on reference identity: the new context must not be the old one.
    assertFalse("reallocated context is a new instance",
        reallocated == provided);
  }

  // ---------------------------------------------------------------------------
  // reset() — default implementation is a no-op
  // Source: CommandRequestAbstract.java:119-121.
  // ---------------------------------------------------------------------------

  /**
   * The default {@code reset()} is documented as a no-op. Pin that invoking it leaves all state
   * untouched.
   */
  @Test
  public void resetIsNoOpOnDefaultImplementation() {
    var req = new StubCommandRequest();
    req.setLimit(7);
    req.setUseCache(true);

    req.reset();

    assertEquals("reset must not clear limit", 7, req.getLimit());
    assertTrue("reset must not clear useCache", req.isUseCache());
  }

  // ---------------------------------------------------------------------------
  // setParameters / convertToParameters — all branches.
  // Source: CommandRequestAbstract.java:71-106.
  //
  // Branches:
  //   (B1) iArgs is null / empty            → setParameters short-circuits; parameters stays null
  //   (B2) single Map arg                   → convertToParameters returns that map directly
  //   (B3) single Object[] arg              → unwraps, loops positionally
  //   (B4) multiple scalar args             → positional map
  //   (B5) Identifiable with valid position → stored as RID (identity)
  //   (B6) Identifiable with invalid pos    → stored as the Identifiable itself
  // ---------------------------------------------------------------------------

  /**
   * {@code setParameters(null)} and {@code setParameters()} (no args) both short-circuit: the
   * guard at line 72 leaves {@code parameters} null.
   */
  @Test
  public void setParametersGuardsNullAndEmptyLeavesParametersNull() {
    var req = new StubCommandRequest();

    req.exposeSetParameters((Object[]) null);
    assertNull("null iArgs must not initialize parameters", req.getParameters());

    req.exposeSetParameters();
    assertNull("empty iArgs must not initialize parameters", req.getParameters());
  }

  /**
   * Single {@link Map} arg is returned by reference (line 81-82) — no copy. This pins that the
   * caller's map is used as-is; mutations on either side are shared. If this behavior changes
   * (e.g., defensive copy is added), the assertion flips at the instanceof check.
   */
  @Test
  public void convertToParametersPassesSingleMapByReference() {
    Map<Object, Object> provided = new HashMap<>();
    provided.put("a", 1);

    var converted = CommandRequestAbstract.convertToParameters(provided);

    assertSame("single Map iArgs must be returned directly, not copied", provided, converted);
  }

  /**
   * Single {@code Object[]} arg is unwrapped (line 85-88) and each element keyed by its index.
   */
  @Test
  public void convertToParametersUnwrapsSingleObjectArray() {
    Object[] packed = new Object[] {"x", 42, null};

    var converted = CommandRequestAbstract.convertToParameters((Object) packed);

    assertEquals(3, converted.size());
    assertEquals("x", converted.get(0));
    assertEquals(42, converted.get(1));
    assertNull(converted.get(2));
  }

  /**
   * Multiple positional scalar args produce a positional map keyed by index.
   */
  @Test
  public void convertToParametersBuildsPositionalMapFromScalars() {
    var converted = CommandRequestAbstract.convertToParameters("a", 2, 3.5);

    assertEquals(3, converted.size());
    assertEquals("a", converted.get(0));
    assertEquals(2, converted.get(1));
    assertEquals(3.5, converted.get(2));
  }

  /**
   * Identifiable arg with {@code isValidPosition() == true} is replaced by its RID (line 95-100).
   * This lets SQL param-binding use the compact RID representation instead of the full record.
   */
  @Test
  public void convertToParametersReplacesValidIdentifiableWithRid() {
    var validRid = new RecordId(12, 34L);
    assertTrue("precondition: RecordId with non-invalid position is valid",
        validRid.isValidPosition());
    Identifiable identifiable = new FixedIdentifiable(validRid);

    var converted = CommandRequestAbstract.convertToParameters(identifiable);

    assertEquals(1, converted.size());
    assertSame("valid-position Identifiable must be replaced by its RID",
        validRid, converted.get(0));
  }

  /**
   * Identifiable arg with {@code isValidPosition() == false} stays as the Identifiable instance
   * because the replacement branch at line 95-100 is gated on valid position.
   */
  @Test
  public void convertToParametersKeepsInvalidIdentifiableAsIs() {
    // collectionPosition = -1 (COLLECTION_POS_INVALID) so isValidPosition() is false.
    var invalidRid = new RecordId(-1, -1L);
    assertFalse("precondition: RecordId with invalid position", invalidRid.isValidPosition());
    Identifiable identifiable = new FixedIdentifiable(invalidRid);

    var converted = CommandRequestAbstract.convertToParameters(identifiable);

    assertEquals(1, converted.size());
    assertSame("invalid-position Identifiable must remain unchanged",
        identifiable, converted.get(0));
  }

  /**
   * {@code setParameters(single-map)} through the public instance surface stores the map in
   * {@code parameters} via {@code convertToParameters}. This pins the whole setParameters →
   * convertToParameters → parameters-field pipeline.
   *
   * <p>Note: {@code setParameters} wraps its caller in {@code Object...}, so calling
   * {@code setParameters(singleMap)} produces an {@code Object[]{singleMap}} — which
   * {@code convertToParameters} detects as "single Map arg" and returns directly.
   */
  @Test
  public void setParametersStoresSingleMapDirectly() {
    var req = new StubCommandRequest();
    Map<Object, Object> provided = new HashMap<>();
    provided.put("k", "v");

    req.exposeSetParameters((Object) provided);

    assertSame("parameters field holds the caller's map by reference",
        provided, req.getParameters());
  }

  // ---------------------------------------------------------------------------
  // Minimal Identifiable used only to exercise convertToParameters branches.
  // Wraps a RecordId — sufficient because the branch checks RecordIdInternal shape.
  // ---------------------------------------------------------------------------

  private static final class FixedIdentifiable implements Identifiable {
    private final RecordId rid;

    FixedIdentifiable(RecordId rid) {
      this.rid = rid;
    }

    @Nonnull
    @Override
    public RID getIdentity() {
      return rid;
    }

    @Override
    public int compareTo(@Nonnull Identifiable o) {
      return rid.compareTo(o);
    }
  }
}
