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
package com.jetbrains.youtrackdb.internal.core.metadata.function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Drives the {@link Function} record API directly to exercise the entity round-trip
 * ({@code toEntity} / {@code fromEntity}), the three-overload constructor surface (default,
 * entity-wrap, RID-load), the field-setter contracts (name, code, language, parameters,
 * idempotent, callback), and the deprecated callback-based {@link Function#executeInContext}
 * paths that {@link FunctionLibraryTest} does not reach.
 *
 * <p>The class extends {@link DbTestBase} because {@code Function} is an
 * {@link com.jetbrains.youtrackdb.internal.core.type.IdentityWrapper IdentityWrapper}: every
 * constructor allocates an {@code OFunction} entity through the active session, and {@code
 * save(session)} requires an active transaction. Tests that cover the no-session POJO surface
 * (e.g., {@link FunctionUtilWrapperTest}) live elsewhere.
 *
 * <p>Per the project's interface-dispatch trap: tests that drive library-side dispatch use the
 * public {@link FunctionLibrary} interface from
 * {@code session.getMetadata().getFunctionLibrary()}, not the concrete
 * {@link FunctionLibraryProxy}. The {@link Function} class itself is a record-shape API, not a
 * proxy, so direct constructor calls are appropriate here.
 */
public class FunctionRecordRoundTripTest extends DbTestBase {

  /**
   * Default constructor seeds {@code language = "SQL"} per the {@link Function} Javadoc, even
   * before any property is set. Pinning this default catches a future regression that switches
   * the implicit language.
   */
  @Test
  public void defaultConstructorSeedsSqlLanguage() {
    session.begin();
    var f = new Function(session);
    assertEquals("SQL", f.getLanguage());
    assertNull(f.getName());
    assertNull(f.getCode());
    assertNull(f.getParameters());
    assertFalse(f.isIdempotent());
    assertNull(f.getCallback());
    session.rollback();
  }

  /**
   * Setters return {@code this} for fluent chaining (every setter that returns {@code Function})
   * and overwrite the stored field. {@code setCode} and {@code setLanguage} are void — the
   * inconsistency is documented here so a future clean-up that adds a fluent return is a
   * deliberate event.
   */
  @Test
  public void settersAreFluentWhereTheyReturnFunctionAndOverwriteFields() {
    session.begin();
    var f = new Function(session);

    var nameRet = f.setName("Foo");
    var paramsRet = f.setParameters(List.of("a", "b"));
    var idemRet = f.setIdempotent(true);

    // Setters that return Function are fluent.
    assertSame(f, nameRet);
    assertSame(f, paramsRet);
    assertSame(f, idemRet);

    // Void setters mutate but don't return — pinned by absence of compile-time assertion.
    f.setCode("RETURN 1");
    f.setLanguage("javascript");

    assertEquals("Foo", f.getName());
    assertEquals("RETURN 1", f.getCode());
    assertEquals("javascript", f.getLanguage());
    assertEquals(List.of("a", "b"), f.getParameters());
    assertTrue(f.isIdempotent());
    session.rollback();
  }

  /**
   * Save → reload via the RID-loading constructor preserves every field through the entity
   * round-trip. The reload constructor takes the new session's identity, loads the entity, and
   * re-derives the in-memory fields via {@code fromEntity}.
   */
  @Test
  public void saveAndReloadRoundTripsEveryProperty() {
    session.begin();
    var original = new Function(session);
    original.setName("RoundTripFn");
    original.setCode("SELECT 1");
    original.setLanguage("sql");
    original.setParameters(List.of("x", "y", "z"));
    original.setIdempotent(true);
    original.save(session);
    var rid = (RecordIdInternal) original.getIdentity();
    session.commit();

    // Reload through the RID constructor — exercises the third Function constructor and the
    // fromEntity de-serialiser path.
    session.begin();
    var reloaded = new Function(session, rid);
    assertEquals("RoundTripFn", reloaded.getName());
    assertEquals("SELECT 1", reloaded.getCode());
    assertEquals("sql", reloaded.getLanguage());
    assertEquals(List.of("x", "y", "z"), reloaded.getParameters());
    assertTrue(reloaded.isIdempotent());
    session.rollback();
  }

  /**
   * Round-trip the entity-wrapping constructor: load the entity directly and pass it to the
   * {@code Function(EntityImpl)} ctor. Distinct from the RID constructor in that the active
   * transaction's load is performed by the caller, not inside the constructor.
   */
  @Test
  public void entityConstructorRebuildsFunctionFromExistingEntity() {
    session.begin();
    var original = new Function(session);
    original.setName("EntityCtorFn");
    original.setCode("RETURN 42");
    original.setLanguage("sql");
    original.save(session);
    var rid = original.getIdentity();
    session.commit();

    session.begin();
    var entity = (EntityImpl) session.loadEntity(rid);
    var rebuilt = new Function(entity);
    assertEquals("EntityCtorFn", rebuilt.getName());
    assertEquals("RETURN 42", rebuilt.getCode());
    assertEquals("sql", rebuilt.getLanguage());
    // No parameters set → fromEntity stores null (the EmbeddedList is empty / not present).
    // Coverage gate: the idempotent default is false when the property is absent.
    assertFalse(rebuilt.isIdempotent());
    session.rollback();
  }

  /**
   * The {@code idempotent} de-serialiser falls back to {@code false} when the stored property
   * is null. Pin so a future change that switches the default to {@code true} is a deliberate,
   * visible event.
   */
  @Test
  public void idempotentDefaultsToFalseWhenStoredPropertyIsNull() {
    session.begin();
    var original = new Function(session);
    original.setName("IdemDefaultFn");
    // Don't call setIdempotent — leaves the field at its default false.
    original.save(session);
    var rid = (RecordIdInternal) original.getIdentity();
    session.commit();

    session.begin();
    var reloaded = new Function(session, rid);
    assertFalse(reloaded.isIdempotent());
    session.rollback();
  }

  /**
   * The setter chain {@code setIdempotent(true)} round-trips through the entity. Pin both arms
   * (false above, true here) to lock the boolean serialisation.
   */
  @Test
  public void idempotentTrueSurvivesRoundTrip() {
    session.begin();
    var original = new Function(session);
    original.setName("IdemTrueFn");
    original.setIdempotent(true);
    original.save(session);
    var rid = (RecordIdInternal) original.getIdentity();
    session.commit();

    session.begin();
    var reloaded = new Function(session, rid);
    assertTrue(reloaded.isIdempotent());
    session.rollback();
  }

  /**
   * {@link Function#executeInContext(com.jetbrains.youtrackdb.internal.core.command.CommandContext,
   * Object...)} short-circuits when a {@link CallableFunction} callback is set: no script
   * executor lookup happens, the callback receives the positional-to-named-param map, and its
   * return value is the call's result.
   *
   * <p>Pinning this branch covers a path that would otherwise require a full SQL parser /
   * script-executor stack to drive.
   */
  @Test
  public void executeInContextWithCallbackShortCircuitsAndPassesArgs() {
    session.begin();
    var f = new Function(session);
    f.setName("CallbackFn");
    f.setParameters(List.of("a", "b"));

    final Map<Object, Object>[] received = new Map[1];
    CallableFunction<Object, Map<Object, Object>> callback = args -> {
      received[0] = args;
      return "callback-ran";
    };
    f.setCallback(callback);
    assertSame(callback, f.getCallback());

    var ctx = new BasicCommandContext(session);
    var result = f.executeInContext(ctx, 1, "two");
    assertEquals("callback-ran", result);
    assertNotNull(received[0]);
    // Parameters list is honoured by index — first arg goes to "a", second to "b".
    assertEquals(1, received[0].get("a"));
    assertEquals("two", received[0].get("b"));
    session.rollback();
  }

  /**
   * The deprecated {@link Function#execute(Object...)} convenience overload re-creates a
   * {@link BasicCommandContext} internally with no session — and the
   * {@code executeInContext(ctx, args)} body unconditionally evaluates
   * {@code iContext.getDatabaseSession()} before checking the callback. The session-less
   * context throws {@link com.jetbrains.youtrackdb.internal.core.exception.DatabaseException
   * DatabaseException} from
   * {@link BasicCommandContext#getDatabaseSession()}.
   *
   * <p>Pinning this throw arm catches a future change that re-orders the early-return so the
   * callback short-circuit happens before the session lookup (which would let session-less
   * callback execution succeed). The test asserts on the exception's message fragment so it
   * is not coupled to the BaseException subtype hierarchy.
   */
  @Test
  public void executeWithoutContextThrowsBecauseSessionLookupRunsBeforeCallback() {
    session.begin();
    var f = new Function(session);
    f.setName("ConvenienceFn");
    f.setCallback(args -> args.size());
    try {
      @SuppressWarnings("deprecation")
      var unused = f.execute("alpha", "beta", "gamma");
      org.junit.Assert.fail(
          "Expected the no-context overload to throw on getDatabaseSession() — actual: "
              + unused);
    } catch (RuntimeException expected) {
      assertTrue(
          "Expected message to mention SQL context, got: " + expected.getMessage(),
          expected.getMessage() != null
              && expected.getMessage().contains("No database session"));
    } finally {
      session.rollback();
    }
  }

  /**
   * When {@code parameters} is null but {@code iArgs} are provided, the implementation falls back
   * to synthetic names ({@code param0}, {@code param1}, ...). Pin the synthetic-name branch
   * because it differs from the parameters-list path covered above.
   */
  @Test
  public void executeInContextSynthesisesParamNamesWhenParametersListIsNull() {
    session.begin();
    var f = new Function(session);
    f.setName("SyntheticParamsFn");
    // No parameters list — every arg gets a synthetic name.
    final Map<Object, Object>[] received = new Map[1];
    f.setCallback(args -> {
      received[0] = args;
      return null;
    });
    f.executeInContext(new BasicCommandContext(session), "first", 2);
    assertNotNull(received[0]);
    assertEquals("first", received[0].get("param0"));
    assertEquals(2, received[0].get("param1"));
    session.rollback();
  }

  /**
   * When the parameters list is shorter than the args array, the surplus args use synthetic
   * names from {@code paramN} starting at the index where the explicit list runs out.
   */
  @Test
  public void executeInContextMixesNamedAndSyntheticArgsWhenParametersListShorter() {
    session.begin();
    var f = new Function(session);
    f.setName("MixedParamsFn");
    f.setParameters(List.of("a"));
    final Map<Object, Object>[] received = new Map[1];
    f.setCallback(args -> {
      received[0] = args;
      return null;
    });
    f.executeInContext(new BasicCommandContext(session), 10, 20, 30);
    assertEquals(10, received[0].get("a"));
    assertEquals(20, received[0].get("param1"));
    assertEquals(30, received[0].get("param2"));
    session.rollback();
  }

  /**
   * The {@code Map<String, Object>} overload of {@code executeInContext} reads parameter names
   * from the map by parameter list order — preserves the positional contract documented in the
   * Javadoc. With a callback, it short-circuits without a script executor.
   */
  @Test
  public void executeInContextWithMapOverloadHonoursParameterOrder() {
    session.begin();
    var f = new Function(session);
    f.setName("MapOverloadFn");
    f.setParameters(List.of("x", "y"));
    final Map<Object, Object>[] received = new Map[1];
    f.setCallback(args -> {
      received[0] = args;
      return "ok";
    });

    var ctx = new BasicCommandContext(session);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("x", 100);
    args.put("y", 200);
    args.put("ignored", 300);
    var result = f.executeInContext(ctx, args);
    assertEquals("ok", result);
    // The map overload only forwards entries listed in parameters — "ignored" is dropped.
    assertEquals(100, received[0].get("x"));
    assertEquals(200, received[0].get("y"));
    session.rollback();
  }

  /**
   * The {@link Function#execute(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
   * Map)} variant short-circuits to the callback without consulting the script executor and
   * without wrapping the call in {@code computeInTx}. Pinning the callback arm of this overload
   * keeps the deprecated three-arg surface exercised.
   */
  @Test
  public void executeWithSessionAndArgMapShortCircuitsToCallback() {
    session.begin();
    var f = new Function(session);
    f.setName("SessionArgMapFn");
    f.setCallback(args -> 4242);
    @SuppressWarnings("deprecation")
    var result = f.execute(session, new LinkedHashMap<>(Map.of("k", "v")));
    assertEquals(4242, result);
    session.rollback();
  }

  /**
   * Setting and clearing the callback toggles the field — pin the null-set arm so a future
   * regression that retains the previous callback after a null-set is caught.
   */
  @Test
  public void callbackCanBeClearedToNull() {
    session.begin();
    var f = new Function(session);
    f.setCallback(args -> "x");
    assertNotNull(f.getCallback());
    f.setCallback(null);
    assertNull(f.getCallback());
    session.rollback();
  }
}
