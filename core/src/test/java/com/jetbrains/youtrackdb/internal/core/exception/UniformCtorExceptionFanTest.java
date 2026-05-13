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
package com.jetbrains.youtrackdb.internal.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Parameterized fan covering the bucket-(a) live throw-bearing exception leaves under {@code
 * core/exception/} that share a uniform constructor shape: a {@code (String message)} or {@code
 * (String dbName, String message)} or {@code (DatabaseSessionEmbedded session, String message)}
 * variant plus the canonical {@code copy} constructor used to deserialise the exception on the
 * remote-protocol client side.
 *
 * <p>Bucket selection is the load-bearing part of finding A2/T4: every leaf below has at least
 * one PSI-confirmed {@code throw new …} or {@code new …(…)} production site (PSI find-usages run
 * against {@code mainScope} of the project — the count for each leaf is recorded in the cluster
 * classification table at the head of {@code track-22a.md}). Leaves with zero production-side
 * instantiation OR with bespoke ctor shapes (RID args, ParseException args, component args, custom
 * (text, position) args) are excluded — they live in {@code *DeadCodeTest} files (throw-site-zero
 * bucket d) or in their own bespoke per-class tests (bucket b).
 *
 * <p>Each row pins:
 * <ul>
 *   <li>The available {@code (String message)} ctor — message round-trips through {@link
 *       Throwable#getMessage()} (or, for {@link CoreException} subclasses, the message contains
 *       the input substring after the {@code dbName}/{@code componentName}/{@code errorCode}
 *       decoration).
 *   <li>The available {@code (String dbName, String message)} ctor — both the message and the
 *       {@code dbName} round-trip through {@link BaseException#getDbName()}.
 *   <li>The available {@code (DatabaseSessionEmbedded, String message)} ctor — when present,
 *       passing {@code null} as the session must NOT throw NPE during construction (the ctor
 *       checks for null before dereferencing).
 *   <li>The copy ctor — message and dbName flow from the original to the copy via {@code
 *       super(exception)}.
 * </ul>
 *
 * <p>The parameter table also captures the leaf's expected supertype (always {@link
 * BaseException} except {@code InvalidIndexEngineIdException} which extends {@link Exception}
 * directly) so a future refactor that re-parents an exception breaks loudly instead of silently
 * cascading through unrelated catch sites.
 *
 * <p>The fourth column — {@code declaredCtors} — names the ctor shapes that each row's leaf
 * class explicitly declares, drawn from {@link Shape}. The per-shape tests use this set to
 * decide whether to exercise the shape (by calling {@code getConstructor(...)}, which raises
 * {@link NoSuchMethodException} loudly if the row's class no longer declares that ctor) or to
 * skip via {@link Assume#assumeTrue}. Without the set, a row that loses an expected ctor would
 * silently no-op the test instead of failing — exactly the regression mode finding TB-2 flagged.
 *
 * <p>Pure unit-level — no {@code DbTestBase} required. Standalone parameterized run.
 */
@RunWith(Parameterized.class)
public class UniformCtorExceptionFanTest {

  /**
   * Names the constructor shapes covered by the parameterized fan. Each row's
   * {@code declaredCtors} EnumSet enumerates which shapes its class actually declares; per-shape
   * tests skip rows that don't declare the shape and exercise (and fail loudly) rows that do.
   */
  private enum Shape {
    /** {@code (String message)}. */
    MESSAGE,
    /** {@code (String dbName, String message)}. */
    DBNAME_MESSAGE,
    /** {@code (DatabaseSessionEmbedded session, String message)}. */
    SESSION_MESSAGE,
    /** {@code (LeafType exception)} — copy ctor used by remote-protocol deserialisation. */
    COPY
  }

  // Each row: (label, exception class, expected ancestor in the exception hierarchy,
  // EnumSet<Shape> of ctor shapes the leaf class declares).
  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
            // -- (String dbName, String message) + copy + maybe (String message) + maybe session --
            {"AcquireTimeoutException", AcquireTimeoutException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.COPY)},
            {"BackupInProgressException", BackupInProgressException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            {
                "CollectionDoesNotExistException",
                CollectionDoesNotExistException.class,
                BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)
            },
            {"CommandExecutionException", CommandExecutionException.class, BaseException.class,
                EnumSet.allOf(Shape.class)},
            {"CommandInterruptedException", CommandInterruptedException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.SESSION_MESSAGE, Shape.COPY)},
            {"CommitSerializationException",
                CommitSerializationException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            // CommonStorageComponentException has bespoke (message, componentName, dbName) ctor
            // — covered separately in CommonStorageComponentExceptionTest.
            {"ConfigurationException", ConfigurationException.class, BaseException.class,
                EnumSet.allOf(Shape.class)},
            {"DatabaseException", DatabaseException.class, BaseException.class,
                EnumSet.allOf(Shape.class)},
            {"EncryptionKeyAbsentException",
                EncryptionKeyAbsentException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"FetchException", FetchException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"InvalidDatabaseNameException",
                InvalidDatabaseNameException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.COPY)},
            {"InvalidInstanceIdException", InvalidInstanceIdException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE)},
            {
                "InvalidStorageEncryptionKeyException",
                InvalidStorageEncryptionKeyException.class,
                BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.DBNAME_MESSAGE, Shape.COPY)
            },
            {
                "ModificationOperationProhibitedException",
                ModificationOperationProhibitedException.class,
                BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)
            },
            {"NoTxRecordReadException", NoTxRecordReadException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"SchemaException", SchemaException.class, BaseException.class,
                EnumSet.allOf(Shape.class)},
            {"SchemaNotCreatedException", SchemaNotCreatedException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"SecurityAccessException", SecurityAccessException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"SecurityException", SecurityException.class, BaseException.class,
                EnumSet.allOf(Shape.class)},
            {"SequenceException", SequenceException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"SequenceLimitReachedException",
                SequenceLimitReachedException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE)},
            {"SerializationException", SerializationException.class, BaseException.class,
                EnumSet.allOf(Shape.class)},
            {"StorageDoesNotExistException",
                StorageDoesNotExistException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"StorageException", StorageException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"StorageExistsException", StorageExistsException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"TransactionBlockedException", TransactionBlockedException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.DBNAME_MESSAGE, Shape.COPY)},
            {"TransactionException", TransactionException.class, BaseException.class,
                EnumSet.allOf(Shape.class)},
            {"ValidationException", ValidationException.class, BaseException.class,
                EnumSet.of(Shape.DBNAME_MESSAGE, Shape.SESSION_MESSAGE, Shape.COPY)},
            {"WriteCacheException", WriteCacheException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.DBNAME_MESSAGE, Shape.COPY)},
            // -- Standalone "throws Exception" leaf (does not extend BaseException) --
            {
                "InvalidIndexEngineIdException",
                InvalidIndexEngineIdException.class,
                Exception.class,
                EnumSet.of(Shape.MESSAGE)
            },
            // QueryParsingException extends CommandSQLParsingException; the bucket-a path covers
            // its (String message), (String dbName, String message), and copy ctors. The extended
            // (String dbName, String iMessage, String iText, int iLine, int iColumn) ctor is
            // exercised by the bespoke CommandSQLParsingException test alongside its parent.
            {"QueryParsingException", QueryParsingException.class, BaseException.class,
                EnumSet.of(Shape.MESSAGE, Shape.DBNAME_MESSAGE, Shape.COPY)},
        // SessionNotActivatedException — bespoke (String dbName) ctor, NOT (String message);
        //   covered by SessionNotActivatedExceptionTest.
        // TooBigIndexKeyException — bespoke (dbName, message, componentName) ctor; covered
        //   by TooBigIndexKeyExceptionTest.
        // LinksConsistencyException — (DatabaseSessionEmbedded, String) ctor dereferences
        //   the session unconditionally, so the fan's null-session probe is invalid; covered
        //   by LinksConsistencyExceptionTest.
        });
  }

  @Parameter(0)
  public String label;

  @Parameter(1)
  public Class<? extends Throwable> exceptionClass;

  @Parameter(2)
  public Class<?> expectedAncestor;

  @Parameter(3)
  public EnumSet<Shape> declaredCtors;

  /**
   * Each row's exception class must extend the documented ancestor — typically {@link
   * BaseException}, except {@link InvalidIndexEngineIdException} which extends {@link Exception}
   * directly because it is a checked exception thrown by the {@code IndexEngineFactory} SPI.
   */
  @Test
  public void exceptionClassExtendsExpectedAncestor() {
    assertThat(expectedAncestor.isAssignableFrom(exceptionClass))
        .as("%s must extend %s", exceptionClass.getName(), expectedAncestor.getName())
        .isTrue();
  }

  /**
   * The copy ctor — {@code (X exception)} where {@code X} is the leaf class — is part of the
   * documented "remote client deserialisation" shape on {@link BaseException}. Every bucket-(a)
   * leaf flagged as declaring {@link Shape#COPY} must declare one. Round-trips both message and
   * dbName. Skipped for {@link Exception}-derived leaves because they have no canonical copy ctor
   * in this hierarchy and skipped for any row whose {@code declaredCtors} omits {@code COPY}.
   *
   * <p>If a row's class is later refactored to drop the COPY ctor, update the row's
   * {@code declaredCtors} EnumSet in lockstep — otherwise the {@code getConstructor} call below
   * raises {@link NoSuchMethodException} and fails this test loudly (the contract this finding
   * was added to enforce).
   */
  @Test
  public void copyConstructorPreservesMessageAndDbNameWhenAvailable() throws Exception {
    Assume.assumeTrue(
        "row " + label + " does not declare COPY ctor — skipped by design",
        declaredCtors.contains(Shape.COPY));
    if (!BaseException.class.isAssignableFrom(exceptionClass)) {
      return;
    }
    // declaredCtors says this row HAS a COPY ctor; if the class no longer declares it, the
    // getConstructor below raises NoSuchMethodException and the test fails loudly — this is the
    // regression-detection contract.
    Constructor<?> copy = exceptionClass.getConstructor(exceptionClass);

    // Try first to build the source via the (String dbName, String message) ctor so the dbName
    // is non-null. If that ctor doesn't exist, fall back to the message-only path — in which
    // case dbName remains null and we only assert message round-trip.
    Throwable original;
    boolean dbNameSet;
    if (declaredCtors.contains(Shape.DBNAME_MESSAGE)) {
      var ctor = exceptionClass.getConstructor(String.class, String.class);
      original = (Throwable) ctor.newInstance("originalDb", "original-msg");
      dbNameSet = true;
    } else {
      original = newInstanceWithMessageAndDbName(exceptionClass, "originalDb", "original-msg");
      dbNameSet = false;
    }
    if (original == null) {
      return;
    }
    var copyExc = (Throwable) copy.newInstance(original);
    assertThat(copyExc.getMessage()).contains("original-msg");
    if (copyExc instanceof BaseException baseCopy && dbNameSet) {
      assertThat(baseCopy.getDbName()).isEqualTo("originalDb");
    }
  }

  /**
   * The {@code (String message)} ctor — when declared — must round-trip the message via {@link
   * Throwable#getMessage()}. {@link CoreException} subclasses decorate the message with {@code
   * dbName} / {@code componentName} / {@code errorCode}; for those we assert message
   * containment rather than equality. Rows whose {@code declaredCtors} omits {@link Shape#MESSAGE}
   * are skipped via {@link Assume}. Rows that declare the shape and lose it later see
   * {@link NoSuchMethodException} surface from {@code getConstructor} and fail loudly.
   */
  @Test
  public void messageOnlyConstructorRoundTripsMessageWhenAvailable() throws Exception {
    Assume.assumeTrue(
        "row " + label + " does not declare MESSAGE ctor — skipped by design",
        declaredCtors.contains(Shape.MESSAGE));
    Constructor<?> ctor = exceptionClass.getConstructor(String.class);
    var inst = (Throwable) ctor.newInstance("uniqueMessage-" + label);
    assertThat(inst.getMessage()).contains("uniqueMessage-" + label);
  }

  /**
   * The {@code (String dbName, String message)} ctor — when declared — must round-trip both the
   * message and the dbName. Rows whose {@code declaredCtors} omits {@link Shape#DBNAME_MESSAGE}
   * are skipped; rows that declare the shape and lose it later fail via the
   * {@link NoSuchMethodException} that {@code getConstructor} raises.
   */
  @Test
  public void dbNameAndMessageConstructorRoundTripsBothFieldsWhenAvailable() throws Exception {
    Assume.assumeTrue(
        "row " + label + " does not declare DBNAME_MESSAGE ctor — skipped by design",
        declaredCtors.contains(Shape.DBNAME_MESSAGE));
    Constructor<?> ctor = exceptionClass.getConstructor(String.class, String.class);
    var inst = (Throwable) ctor.newInstance("dbX-" + label, "messageY-" + label);
    assertThat(inst.getMessage()).contains("messageY-" + label);
    if (inst instanceof BaseException baseException) {
      assertThat(baseException.getDbName()).isEqualTo("dbX-" + label);
    }
  }

  /**
   * The {@code (DatabaseSessionEmbedded session, String message)} ctor — when declared — must NOT
   * NPE on a null session input. The implementation checks {@code session != null} before
   * dereferencing {@code session.getDatabaseName()}. Rows whose {@code declaredCtors} omits
   * {@link Shape#SESSION_MESSAGE} are skipped; rows that declare the shape and lose it later
   * fail via the {@link NoSuchMethodException} that {@code getConstructor} raises.
   */
  @Test
  public void sessionAndMessageConstructorAcceptsNullSessionWhenAvailable() throws Exception {
    Assume.assumeTrue(
        "row " + label + " does not declare SESSION_MESSAGE ctor — skipped by design",
        declaredCtors.contains(Shape.SESSION_MESSAGE));
    Constructor<?> ctor =
        exceptionClass.getConstructor(DatabaseSessionEmbedded.class, String.class);
    var inst = (Throwable) ctor.newInstance(null, "sessMessage-" + label);
    assertThat(inst.getMessage()).contains("sessMessage-" + label);
  }

  /**
   * Helper: build an instance via the leaf's most idiomatic ctor for round-trip testing.
   * Selects the first-preferred ctor among the canonical shapes; returns {@code null} if none
   * matched (the caller skips the test in that case).
   */
  private static Throwable newInstanceWithMessageAndDbName(
      Class<? extends Throwable> exceptionClass, String dbName, String message)
      throws InstantiationException, IllegalAccessException, InvocationTargetException {
    // 1. (String dbName, String message)
    try {
      var ctor = exceptionClass.getConstructor(String.class, String.class);
      return (Throwable) ctor.newInstance(dbName, message);
    } catch (NoSuchMethodException ignored) {
      // 2. (String message)
    }
    try {
      var ctor = exceptionClass.getConstructor(String.class);
      return (Throwable) ctor.newInstance(message);
    } catch (NoSuchMethodException ignored) {
      // 3. (DatabaseSessionEmbedded, String message) with null session
    }
    try {
      var ctor = exceptionClass.getConstructor(DatabaseSessionEmbedded.class, String.class);
      return (Throwable) ctor.newInstance(null, message);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }
}
