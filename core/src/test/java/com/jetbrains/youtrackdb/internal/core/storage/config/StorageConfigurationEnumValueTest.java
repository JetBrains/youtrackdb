package com.jetbrains.youtrackdb.internal.core.storage.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

/**
 * Round-trip tests for storage-local settings of enum-typed configuration keys, using
 * {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT} (the only enum-typed key a user sets per
 * storage).
 *
 * <p>The value is written as its constant name when the storage closes and converted back by its
 * declared type when the storage loads. Enum-typed keys need their own conversion on that read path,
 * and a value that names no constant must not make the database unopenable.
 *
 * <p>Marked {@code @Category(SequentialTest)} because the tests mutate the process-wide
 * {@code QUERY_ORDER_BY_NULLS_DEFAULT} global; the default surefire execution runs four test classes
 * in parallel in one virtual machine, so the mutation would leak between classes.
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
   * Regression test for the reopen blocker: storing the enum-typed key on a disk storage used to
   * make the database unopenable, because the shared type conversion on the load path has no enum
   * branch. The value must survive close and reopen.
   */
  @Test
  public void diskDatabaseReopensAfterEnumValueIsStored() {
    storeOnStorage(NULLS_KEY, OrderByNullsDefault.NULLS_LARGEST);

    reopenContext();

    assertEquals(OrderByNullsDefault.NULLS_LARGEST, storageConfiguration().getValue(NULLS_KEY));
  }

  /**
   * A value persisted in lower case (a server property carries plain text) still names the constant:
   * the load path matches constant names case-insensitively, like the global setter does, so the
   * storage honours NULLS_LARGEST rather than silently falling back.
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
   * A stored value that names no constant is skipped with a warning: the database opens, the key is
   * absent from the storage configuration, and the runtime global stays in force. The global is set
   * to NULLS_LARGEST here so a fallback to the declared default would fail the assertion too.
   */
  @Test
  public void storedInvalidValueIsSkippedAndGlobalDefaultApplies() {
    NULLS_KEY.setValue(OrderByNullsDefault.NULLS_LARGEST);
    storeOnStorage(NULLS_KEY, "NOT_A_CONSTANT");

    reopenContext();

    var configuration = storageConfiguration();
    assertFalse(configuration.getContextKeys().contains(NULLS_KEY.getKey()));
    assertEquals(OrderByNullsDefault.NULLS_LARGEST, OrderByNullsUtil.resolveDefault(configuration));
  }

  /**
   * The tolerance is bounded to enum-typed keys: a damaged value of a non-enum key still fails the
   * open loudly instead of being skipped. The concrete exception type is not asserted because the
   * shared conversion helper is deliberately left untouched by this change.
   */
  @Test
  public void storedInvalidValueOfNonEnumKeyStillFailsTheOpen() {
    storeOnStorage(INT_KEY, "not-a-number");

    reopenContext();

    assertThrows(
        Throwable.class,
        () -> youTrackDB.open(databaseName, "admin", DbTestBase.ADMIN_PASSWORD).close());
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

  private void reopenContext() {
    youTrackDB = openContext();
  }

  private YouTrackDBImpl openContext() {
    return (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
  }
}
