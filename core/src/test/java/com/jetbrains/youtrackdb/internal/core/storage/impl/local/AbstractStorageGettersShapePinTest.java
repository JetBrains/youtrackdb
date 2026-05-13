package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Shape pins for the public configuration-passthrough and identity getters on
 * {@link AbstractStorage}. These methods delegate to the underlying
 * {@code StorageConfiguration}; the test asserts each one returns a non-null
 * (or domain-correct) value on a freshly-opened storage so any regression
 * that breaks a delegation is caught in surefire scope.
 *
 * <p>Each test groups closely-related accessors into one method to keep the
 * assertion-per-test ratio readable and to keep the per-test database setup
 * cost amortised.
 */
public class AbstractStorageGettersShapePinTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name) collisions when
  // these tests run in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;
  private AbstractStorage storage;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.MEMORY, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = (AbstractStorage) db.getStorage();
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  @Test
  public void identityGetters_returnConfiguredValues() {
    // Identity-class accessors: name, URL, getUnderlying, status, toString.
    // getName returns the bare database name. getURL is engine-overridable —
    // DirectMemoryStorage prefixes with "memory:" — and toString() returns
    // the raw private url field (no engine prefix). Both are non-blank, both
    // contain the bare name, and toString() returns either the raw url or
    // "?" if url is null.
    assertThat(storage.getName()).isEqualTo(dbName);
    assertThat(storage.getURL()).isNotBlank().contains(dbName);
    assertThat(storage.getUnderlying()).isSameAs(storage);
    assertThat(storage.getStatus()).isEqualTo(Storage.STATUS.OPEN);
    assertThat(storage.toString()).isNotBlank().isNotEqualTo("?");
    assertThat(storage.isAssigningCollectionIds()).isTrue();
  }

  @Test
  public void configurationPassthroughs_returnNonNullValues() {
    // All purely-delegating "getX" methods should return non-null on an open
    // memory storage with default configuration. We probe both the value and
    // the fact that none of them throw NPE on the configuration object.
    assertThat(storage.getContextConfiguration()).isNotNull();
    assertThat(storage.getSchemaRecordId()).isNotBlank();
    assertThat(storage.getCharset()).isNotBlank();
    assertThat(storage.getIndexMgrRecordId()).isNotBlank();
    // TimeZone is @Nullable but must be non-null when the configuration was
    // populated by a regular database lifecycle (the default config provides
    // a JVM-locale time zone).
    assertThat(storage.getTimeZone()).isNotNull();
    assertThat(storage.getDateFormatInstance()).isNotNull();
    assertThat(storage.getDateFormat()).isNotBlank();
    assertThat(storage.getDateTimeFormat()).isNotBlank();
    assertThat(storage.getDateTimeFormatInstance()).isNotNull();
    assertThat(storage.getRecordSerializer()).isNotBlank();
    assertThat(storage.getLocaleCountry()).isNotNull();
    assertThat(storage.getLocaleLanguage()).isNotNull();
  }

  @Test
  public void numericConfigurationGetters_returnSensibleValues() {
    // Numeric configuration accessors with reasonable lower bounds. The exact
    // values depend on defaults that may evolve, so we pin to >0 / >=0.
    assertThat(storage.getRecordSerializerVersion()).isGreaterThanOrEqualTo(0);
    assertThat(storage.getMinimumCollections()).isGreaterThanOrEqualTo(0);
    assertThat(storage.isValidationEnabled()).isIn(Boolean.TRUE, Boolean.FALSE);
  }

  @Test
  public void sessionAccountingGetters_reflectLifecycle() {
    // Session counting and last-close-time bookkeeping. With a single live
    // session the count is at least 1, and lastCloseTime is the
    // construction-time fallback (>0). Closing the session decrements the
    // counter; a second `open` then increments it back.
    long before = storage.getSessionsCount();
    long lastCloseBefore = storage.getLastCloseTime();
    assertThat(before).isGreaterThanOrEqualTo(1L);
    assertThat(lastCloseBefore).isGreaterThan(0L);

    db.close();
    long afterClose = storage.getSessionsCount();
    assertThat(afterClose)
        .as("Closing one session must decrement the active session counter")
        .isLessThan(before);

    // Re-open so @After's drop sequence still works correctly.
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    long afterReopen = storage.getSessionsCount();
    assertThat(afterReopen).isGreaterThanOrEqualTo(afterClose + 1);
  }

  @Test
  public void recordCount_reflectsCollectionState() {
    // countRecords sums approximate record counts across all collections; on
    // a freshly created database this is non-negative and stable across
    // calls.
    long firstCount = storage.countRecords(db);
    long secondCount = storage.countRecords(db);
    assertThat(firstCount).isGreaterThanOrEqualTo(0L);
    assertThat(secondCount).isEqualTo(firstCount);
  }
}
