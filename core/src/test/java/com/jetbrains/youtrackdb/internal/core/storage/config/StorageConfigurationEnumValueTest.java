package com.jetbrains.youtrackdb.internal.core.storage.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

/**
 * Round-trip tests for storage-local settings of enum-typed configuration keys. They use {@link
 * GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT}, the only enum-typed key a user sets per
 * storage.
 *
 * <p>The value is written as its constant name when the storage closes and converted back by its
 * declared type when the storage loads. Enum-typed keys need their own conversion on that read path,
 * and a value that names no constant must not make the database unopenable.
 *
 * <p>Marked {@code @Category(SequentialTest)} because the tests mutate the process-wide
 * {@code QUERY_ORDER_BY_NULLS_DEFAULT} global. The default surefire execution runs four test
 * classes in parallel in one virtual machine, so the mutation would leak between classes.
 */
@Category(SequentialTest.class)
public class StorageConfigurationEnumValueTest {

  private static final GlobalConfiguration NULLS_KEY =
      GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT;

  /** A non-enum key used to prove the tolerant read stays bounded to enum-typed keys. */
  private static final GlobalConfiguration INT_KEY =
      GlobalConfiguration.QUERY_INDEX_ORDERED_MIN_LINKBAG;

  @Rule
  public TestName name = new TestName();

  private YouTrackDBImpl youTrackDB;
  private String databaseName;

  @Before
  public void before() {
    databaseName = name.getMethodName();
    youTrackDB = openContext();
    youTrackDB.create(
        databaseName,
        DatabaseType.DISK,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
  }

  @After
  public void after() {
    NULLS_KEY.resetToDefault();
    INT_KEY.resetToDefault();
    try {
      if (youTrackDB.isOpen() && youTrackDB.exists(databaseName)) {
        youTrackDB.drop(databaseName);
      }
    } catch (RuntimeException e) {
      // A test that deliberately leaves an unopenable database cannot drop it. The database lives
      // under target/, so leaving it behind costs nothing.
    } finally {
      if (youTrackDB.isOpen()) {
        youTrackDB.close();
      }
    }
  }

  /**
   * Regression test for the reopen blocker. Storing the enum-typed key on a disk storage used to
   * make that database unopenable. The shared type conversion on the load path has no enum branch.
   * The value must survive close and reopen.
   */
  @Test
  public void diskDatabaseReopensAfterEnumValueIsStored() {
    storeOnStorage(NULLS_KEY, OrderByNullsDefault.NULLS_LARGEST);

    reopenContext();

    assertEquals(OrderByNullsDefault.NULLS_LARGEST, storageConfiguration().getValue(NULLS_KEY));
  }

  /**
   * A value persisted in lower case still names the constant, and a server property carries plain
   * text. The load path matches constant names while ignoring case, like the global setter does. The
   * storage therefore honours NULLS_LARGEST rather than falling back without a word.
   */
  @Test
  public void storedLowerCaseValueIsAcceptedAndHonoured() {
    NULLS_KEY.setValue(OrderByNullsDefault.NULLS_SMALLEST);
    storeOnStorage(NULLS_KEY, "nulls_largest");

    reopenContext();

    var configuration = storageConfiguration();
    assertEquals(OrderByNullsDefault.NULLS_LARGEST, configuration.getValue(NULLS_KEY));
    assertEquals(
        OrderByNullsDefault.NULLS_LARGEST, OrderByNullsUtil.resolveDefault(configuration));
  }

  /**
   * A stored value that names no constant is skipped, and the database opens. The key is absent from
   * the storage configuration, so the runtime global stays in force. The global is set to
   * NULLS_LARGEST here, so a fallback to the declared default would fail the assertion too. The
   * logged warning has to name the database, the key, the value and the consequence, because it is
   * the operator's only signal.
   */
  @Test
  public void storedInvalidValueIsReportedAndGlobalDefaultApplies() {
    NULLS_KEY.setValue(OrderByNullsDefault.NULLS_LARGEST);
    storeOnStorage(NULLS_KEY, "NOT_A_CONSTANT");

    reopenContext();

    ContextConfiguration configuration;
    try (var logs = LogRecordCollector.attachTo(CollectionBasedStorageConfiguration.class)) {
      configuration = storageConfiguration();
      assertTrue(
          "the skipped value must be reported, captured: " + logs.messages(),
          logs.warnedWithAll(
              databaseName, NULLS_KEY.getKey(), "NOT_A_CONSTANT", "global default applies"));
    }
    assertFalse(configuration.getContextKeys().contains(NULLS_KEY.getKey()));
    assertEquals(OrderByNullsDefault.NULLS_LARGEST, OrderByNullsUtil.resolveDefault(configuration));
  }

  /**
   * A skipped value must not be erased. The store path writes back only what the effective
   * configuration holds. A skipped key would therefore disappear at the next clean close, and the
   * recorded intent would be lost. The second reopen has to report the same value again, which it
   * can only do when the value is still on disk.
   */
  @Test
  public void storedInvalidValueSurvivesACleanClose() {
    storeOnStorage(NULLS_KEY, "NOT_A_CONSTANT");

    reopenContext();
    loadStorage();
    // Clean close: this is the write-back that used to drop the key.
    youTrackDB.close();

    reopenContext();
    try (var logs = LogRecordCollector.attachTo(CollectionBasedStorageConfiguration.class)) {
      loadStorage();
      assertTrue(
          "the value must still be on disk after a clean close, captured: " + logs.messages(),
          logs.warnedWithAll(databaseName, NULLS_KEY.getKey(), "NOT_A_CONSTANT"));
    }
  }

  /**
   * A preserved value must never contradict the operator. Clearing the key is an explicit decision,
   * so the preserved text is dropped at that moment and the next clean close writes nothing back.
   * The reopen after that has to be silent, because the value is gone from disk.
   */
  @Test
  public void clearingTheKeyDropsThePreservedValue() {
    storeOnStorage(NULLS_KEY, "NOT_A_CONSTANT");

    reopenContext();
    try (var session = youTrackDB.open(databaseName, "admin", DbTestBase.ADMIN_PASSWORD)) {
      var configuration = session.getStorage().getContextConfiguration();
      // The operator corrects the setting and then decides to drop it altogether.
      configuration.setValue(NULLS_KEY, OrderByNullsDefault.NULLS_LARGEST);
      configuration.setValue(NULLS_KEY, null);
    }
    youTrackDB.close();

    reopenContext();
    try (var logs = LogRecordCollector.attachTo(CollectionBasedStorageConfiguration.class)) {
      loadStorage();
      assertFalse(
          "a cleared key must not come back, captured: " + logs.messages(),
          logs.warnedWithAll(NULLS_KEY.getKey(), "NOT_A_CONSTANT"));
    }
    assertFalse(storageConfiguration().getContextKeys().contains(NULLS_KEY.getKey()));
  }

  /**
   * Surrounding whitespace is not tolerated, because the global setter does not tolerate it either.
   * A padded value is treated as unreadable, so the global default applies and the value is kept for
   * a later corrected reading.
   */
  @Test
  public void storedPaddedValueIsRejectedLikeTheGlobalSetter() {
    NULLS_KEY.setValue(OrderByNullsDefault.NULLS_SMALLEST);
    storeOnStorage(NULLS_KEY, " NULLS_LARGEST ");

    reopenContext();

    var configuration = storageConfiguration();
    assertFalse(configuration.getContextKeys().contains(NULLS_KEY.getKey()));
    assertEquals(
        OrderByNullsDefault.NULLS_SMALLEST, OrderByNullsUtil.resolveDefault(configuration));
  }

  /**
   * The tolerance is bounded to enum-typed keys. A damaged value of a non-enum key still fails the
   * open. The failure has to come from the configuration load, not from anything else the open does.
   * The open reports it as a refusal to open the database, with the load error as a cause.
   */
  @Test
  public void storedInvalidValueOfNonEnumKeyStillFailsTheOpen() {
    storeOnStorage(INT_KEY, "not-a-number");

    reopenContext();

    var failure =
        assertThrows(
            DatabaseException.class,
            () -> youTrackDB.open(databaseName, "admin", DbTestBase.ADMIN_PASSWORD).close());
    assertTrue(
        "the open must be refused, message was: " + failure.getMessage(),
        failure.getMessage().contains("Cannot open database"));
    assertTrue(
        "the configuration load must be the cause, chain was: " + causeMessages(failure),
        causeMessages(failure).contains("Can not load storage configuration"));
  }

  /** Joins the messages of a failure and of every cause below it, for one readable assertion. */
  private static String causeMessages(Throwable failure) {
    var text = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      text.append(current.getMessage()).append(" | ");
    }
    return text.toString();
  }

  /**
   * Sets a value on the open storage's context configuration and closes the whole embedded context,
   * which is what flushes the configuration property to disk.
   */
  private void storeOnStorage(GlobalConfiguration key, Object value) {
    try (var session = youTrackDB.open(databaseName, "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.getStorage().getContextConfiguration().setValue(key, value);
    }
    youTrackDB.close();
  }

  /** Reads the persisted configuration back through a freshly opened session. */
  private ContextConfiguration storageConfiguration() {
    try (var session = youTrackDB.open(databaseName, "admin", DbTestBase.ADMIN_PASSWORD)) {
      return session.getStorage().getContextConfiguration();
    }
  }

  /** Opens and closes a session, which makes the current context load the storage. */
  private void loadStorage() {
    youTrackDB.open(databaseName, "admin", DbTestBase.ADMIN_PASSWORD).close();
  }

  private void reopenContext() {
    youTrackDB = openContext();
  }

  private YouTrackDBImpl openContext() {
    return (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
  }
}
