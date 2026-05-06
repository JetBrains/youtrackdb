package com.jetbrains.youtrackdb.internal.core.metadata.function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import org.junit.Test;

/**
 * Tests function library operations including creation, retrieval, drop, and rename via the
 * public {@link FunctionLibrary} interface.
 *
 * <p>The {@link FunctionLibrary}-interface accessor
 * ({@code session.getMetadata().getFunctionLibrary()}) routes through
 * {@link FunctionLibraryProxy}, which delegates to {@link FunctionLibraryImpl}. Tests drive
 * the public interface directly per the schema-package interface-dispatch trap: grep on
 * {@code FunctionLibraryProxy.createFunction} would suggest 0 callers because dispatch flows
 * through the {@link FunctionLibrary} interface. The tests here exercise the proxy path
 * indirectly while pinning the user-visible contract.
 */
public class FunctionLibraryTest extends DbTestBase {

  // ------------------------------------------------------------------------
  // Pre-existing tests (kept intact to preserve their exact assertions).
  // ------------------------------------------------------------------------

  @Test
  public void testSimpleFunctionCreate() {
    var func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
    assertNotNull(func);
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNotNull(func);
  }

  @Test(expected = FunctionDuplicatedException.class)
  public void testDuplicateFunctionCreate() {
    var func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
    assertNotNull(func);
    session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
  }

  @Test
  public void testFunctionCreateDrop() {
    var func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
    assertNotNull(func);
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNotNull(func);
    session.getMetadata().getFunctionLibrary().dropFunction(session, "TestFunc");
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNull(func);
    func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc1");
    session.begin();
    session.getMetadata().getFunctionLibrary().dropFunction(session, func);
    session.commit();
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNull(func);
  }

  // ------------------------------------------------------------------------
  // Residual-coverage additions for FunctionLibraryImpl
  // (82.2% line / 50.0% branch pre-additions) and FunctionLibraryProxy.
  // ------------------------------------------------------------------------

  /**
   * {@code getFunctionNames()} reflects the library state and uppercases the registered name —
   * pre-existing coverage exercises {@code getFunction} but not the names-set view.
   */
  @Test
  public void getFunctionNamesReflectsRegisteredFunctions() {
    var library = session.getMetadata().getFunctionLibrary();
    library.createFunction("Alpha");
    library.createFunction("Beta");

    var names = library.getFunctionNames();
    assertNotNull(names);
    // Names are stored upper-cased through the library's Locale.ENGLISH normalisation.
    assertTrue(names.contains("ALPHA"));
    assertTrue(names.contains("BETA"));
  }

  /**
   * Lookup is case-insensitive — both {@code "TestFunc"} and {@code "TESTFUNC"} (and any other
   * case variant) return the same registered function. Pinned to lock the case-folding
   * contract.
   */
  @Test
  public void getFunctionLookupIsCaseInsensitive() {
    var library = session.getMetadata().getFunctionLibrary();
    library.createFunction("MixedCaseFn");
    var lookupSame = library.getFunction(session, "MixedCaseFn");
    var lookupUpper = library.getFunction(session, "MIXEDCASEFN");
    var lookupLower = library.getFunction(session, "mixedcasefn");
    assertNotNull(lookupSame);
    assertNotNull(lookupUpper);
    assertNotNull(lookupLower);
    // All three lookups return the same in-memory wrapper object.
    assertSame(lookupSame, lookupUpper);
    assertSame(lookupSame, lookupLower);
  }

  /**
   * {@code getFunction} returns null for an unregistered name — the absent-key arm of the
   * library map. Distinct from the null-name arm (which is not part of the public contract).
   */
  @Test
  public void getFunctionReturnsNullForUnknownName() {
    var library = session.getMetadata().getFunctionLibrary();
    assertNull(library.getFunction(session, "DoesNotExist"));
  }

  /**
   * {@code dropFunction(session, String)} removes the named function and is idempotent — the
   * pre-existing tests use the {@link Function} overload; this test pins the string overload's
   * post-drop state.
   *
   * <p>Idempotency: dropping a non-existent name throws because the implementation calls
   * {@code function.delete(session)} with a null reference. The contract pin here is "drop
   * removes the entry"; the second-drop NPE arm is documented separately in
   * {@link #dropFunctionByNameThrowsNpeOnAbsentName()}.
   */
  @Test
  public void dropFunctionByNameRemovesEntryFromLibrary() {
    var library = session.getMetadata().getFunctionLibrary();
    library.createFunction("DroppableFn");
    assertNotNull(library.getFunction(session, "DroppableFn"));

    library.dropFunction(session, "DroppableFn");
    assertNull(library.getFunction(session, "DroppableFn"));
    assertFalse(library.getFunctionNames().contains("DROPPABLEFN"));
  }

  /**
   * Documents the existing behaviour of {@code dropFunction(session, String)} when the name
   * is not registered: the implementation looks up the function inside an
   * {@code executeInTx} block and immediately invokes {@code function.delete(session)}, which
   * NPEs because {@code function} is null.
   *
   * <p>Pin the contract so a future change that adds a defensive null check is a deliberate,
   * visible event. The exception is either a direct {@link NullPointerException} or a
   * {@link DatabaseException} whose cause chain contains the NPE — the storage/TX path
   * determines which surface fires first, but a broader catch is rejected because it would
   * mask a future regression that swapped the NPE for an unrelated runtime error (e.g., an
   * {@code IllegalStateException} from a tx-state check) without surfacing the contract drift.
   */
  @Test
  public void dropFunctionByNameThrowsNpeOnAbsentName() {
    var library = session.getMetadata().getFunctionLibrary();
    try {
      library.dropFunction(session, "NoSuchFunctionEverRegistered");
      fail(
          "Expected NullPointerException (or DatabaseException wrapping it) when dropping a"
              + " non-existent function by name; production has no defensive null guard");
    } catch (NullPointerException expected) {
      // Direct NPE inside the executeInTx callback — the documented contract.
    } catch (DatabaseException expected) {
      // The TX layer may wrap the NPE; require it stays visible in the cause chain so a
      // hypothetical refactor that swaps the wrapped exception for an unrelated error fails
      // this test instead of silently passing.
      Throwable cur = expected;
      boolean foundNpe = false;
      while (cur != null) {
        if (cur instanceof NullPointerException) {
          foundNpe = true;
          break;
        }
        cur = cur.getCause();
      }
      assertTrue("DatabaseException must wrap the documented NPE: " + expected, foundNpe);
    }
  }

  /**
   * {@code dropFunction(session, Function)} accepts the function object itself (not the name)
   * and removes it from the library; lookup post-drop returns null. The pre-existing
   * {@link #testFunctionCreateDrop} ends with a stale-name lookup that always returned null
   * regardless of whether the rename succeeded; this test pins the by-object drop contract
   * with an unambiguous lookup.
   */
  @Test
  public void dropFunctionByObjectRemovesEntryFromLibrary() {
    var library = session.getMetadata().getFunctionLibrary();
    var fn = library.createFunction("DroppableObjectFn");

    session.begin();
    library.dropFunction(session, fn);
    session.commit();

    assertNull(library.getFunction(session, "DroppableObjectFn"));
  }

  /**
   * The library mutator surface is non-null per {@code Metadata.getFunctionLibrary}; pin the
   * not-null contract so the proxy plumbing isn't accidentally bypassed.
   */
  @Test
  public void metadataExposesFunctionLibrary() {
    var library = session.getMetadata().getFunctionLibrary();
    assertNotNull(library);
    // The proxy is always returned; concrete subtype is the project's contract.
    assertTrue(library instanceof FunctionLibraryProxy);
  }

  /**
   * Validate the regex inside
   * {@link FunctionLibraryImpl#validateFunctionRecord(com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl)}.
   * The static helper is used by the database-bound entity-create hook to reject malformed
   * function names; pinning both arms (valid / invalid) exercises the regex branch the
   * existing tests do not reach.
   *
   * <p>The validator throws a {@link DatabaseException} on rejection. Construction of an
   * {@link com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl EntityImpl} requires a
   * session; the test uses {@code session.newInstance(...)} so the entity is bound to the
   * test's transaction context.
   */
  @Test
  public void validateFunctionRecordAcceptsLegalNames() {
    session.begin();
    var entity =
        (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
            .newInstance("OFunction");
    entity.setProperty("name", "Legal_Name123");
    FunctionLibraryImpl.validateFunctionRecord(entity);
    session.rollback();
  }

  /**
   * Function names starting with a digit are rejected by the regex
   * {@code [A-Za-z][A-Za-z0-9_]*}.
   */
  @Test(expected = DatabaseException.class)
  public void validateFunctionRecordRejectsNamesStartingWithDigit() {
    session.begin();
    try {
      var entity =
          (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
              .newInstance("OFunction");
      entity.setProperty("name", "1Bad");
      FunctionLibraryImpl.validateFunctionRecord(entity);
    } finally {
      session.rollback();
    }
  }

  /**
   * Function names with spaces are rejected — pin a second invalid case to ensure the regex
   * is not inadvertently relaxed.
   */
  @Test(expected = DatabaseException.class)
  public void validateFunctionRecordRejectsNamesWithSpaces() {
    session.begin();
    try {
      var entity =
          (com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl) session
              .newInstance("OFunction");
      entity.setProperty("name", "Bad Name");
      FunctionLibraryImpl.validateFunctionRecord(entity);
    } finally {
      session.rollback();
    }
  }

  /**
   * Smoke test for the {@link Function#save(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)}
   * → library refresh path: explicitly call {@code setName}/{@code setCode} on the returned
   * record, save, then verify the library still resolves the function and its persisted
   * properties round-trip.
   */
  @Test
  public void createdFunctionRetainsExplicitFieldsAfterSave() {
    var library = session.getMetadata().getFunctionLibrary();
    var fn = library.createFunction("WithBodyFn");
    session.begin();
    fn.setCode("RETURN 1");
    fn.setLanguage("sql");
    fn.save(session);
    session.commit();

    var reloaded = library.getFunction(session, "WithBodyFn");
    assertNotNull(reloaded);
    // The library returns the in-memory wrapper; setCode mutated it directly.
    assertEquals("RETURN 1", reloaded.getCode());
    assertEquals("sql", reloaded.getLanguage());
  }
}
