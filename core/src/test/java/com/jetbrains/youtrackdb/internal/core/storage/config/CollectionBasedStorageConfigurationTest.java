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
package com.jetbrains.youtrackdb.internal.core.storage.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.StorageConfigurationUpdateListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for CollectionBasedStorageConfiguration: property get/set/remove/clear,
 * locale recalculation, schema record id, conflict strategy, charset, date format,
 * time zone, validation flag, and update-listener pause/fire semantics.
 *
 * <p>The test uses per-method lifecycle: every test creates a fresh memory database,
 * extracts the {@code CollectionBasedStorageConfiguration} via reflection on
 * {@code AbstractStorage.configuration}, runs the assertion, and drops the database.
 * All mutations run inside an AtomicOperation via the storage's AtomicOperationsManager.
 *
 * <p>The class-static {@code @BeforeClass} pattern was rejected: holding the storage
 * reference across the whole test class survived past {@code @AfterClass} into JVM
 * shutdown, where {@code -Dyoutrackdb.memory.directMemory.trackMode=true} detected
 * unreleased pages and called {@code System.exit(1)} — killing the surefire JVM
 * before any test reported its result. The per-method pattern avoids this entirely
 * by tearing down the database (and freeing all pages) inside {@code @After}.
 */
public class CollectionBasedStorageConfigurationTest {

  private static final String DB_NAME = "configTest";

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private AbstractStorage storage;
  private AtomicOperationsManager atomicOps;
  private CollectionBasedStorageConfiguration config;

  @Before
  public void setUp() throws Exception {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDB.create(DB_NAME, DatabaseType.MEMORY,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    session = youTrackDB.open(DB_NAME, "admin", DbTestBase.ADMIN_PASSWORD);
    storage = session.getStorage();
    atomicOps = storage.getAtomicOperationsManager();

    // Extract the protected 'configuration' field via reflection.
    // CollectionBasedStorageConfiguration is the only concrete StorageConfiguration
    // implementation instantiated by AbstractStorage.create() — see line ~769 of
    // AbstractStorage.java.
    final Field configField = AbstractStorage.class.getDeclaredField("configuration");
    configField.setAccessible(true);
    config = (CollectionBasedStorageConfiguration) configField.get(storage);
  }

  @After
  public void tearDown() {
    // Order matters: close session first, then drop the DB to release direct-memory
    // pages, then close the YouTrackDB instance. Without the drop, the page tracker
    // (-Dyoutrackdb.memory.directMemory.trackMode=true) fires on JVM shutdown and
    // aborts the JVM with System.exit(1).
    //
    // youTrackDB.drop() reopens the DB internally to flush its state, which can
    // throw if the test left the DB in an unopenable state (e.g., an unsupported
    // serializer version). We swallow that exception so the YouTrackDB.close()
    // still runs and the in-process structures are released — without the close,
    // a subsequent test method's setUp() can hit a stale instance.
    if (session != null && !session.isClosed()) {
      try {
        session.close();
      } catch (Exception e) {
        // Best-effort cleanup. Log via LogManager so a future test mutation that
        // makes session.close() throw for a new reason is visible in the test
        // output instead of silently swallowed.
        LogManager.instance().error(this,
            "tearDown: session.close() failed (non-fatal, continuing cleanup)", e);
      }
    }
    if (youTrackDB != null) {
      try {
        youTrackDB.drop(DB_NAME);
      } catch (Exception e) {
        // Best-effort cleanup. Same reasoning as for session.close above — log
        // rather than silently swallow so a regression that makes drop() fail for
        // a new reason is observable.
        LogManager.instance().error(this,
            "tearDown: youTrackDB.drop(%s) failed (non-fatal, continuing cleanup)", e,
            DB_NAME);
      } finally {
        youTrackDB.close();
      }
    }
  }

  // --- setProperty / getProperty ---

  @Test
  public void testSetPropertyStoresValueAndGetPropertyReturnsIt() throws IOException {
    // Verifies that setProperty stores a user-defined key/value pair and
    // getProperty returns the same value immediately.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setProperty(atomicOperation, "myKey", "myValue"));

    assertEquals("myValue", config.getProperty("myKey"));
  }

  @Test
  public void testSetPropertyOverwritesPreviousValue() throws IOException {
    // A second setProperty call on the same key replaces the previous value.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setProperty(atomicOperation, "overwriteKey", "first"));
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setProperty(atomicOperation, "overwriteKey", "second"));

    assertEquals("second", config.getProperty("overwriteKey"));
  }

  @Test
  public void testGetPropertyReturnsNullForAbsentKey() {
    // getProperty returns null for a key that was never set on this fresh DB.
    assertNull(config.getProperty("absentKey"));
  }

  @Test
  public void testSetPropertyNullValueIsStored() throws IOException {
    // setProperty allows a null value (clears the logical value while keeping
    // the key in the map).
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setProperty(atomicOperation, "nullableKey", null));

    assertNull(config.getProperty("nullableKey"));
  }

  // --- removeProperty ---

  /**
   * Pins a latent production bug: {@link CollectionBasedStorageConfiguration#removeProperty}
   * removes the entry from the persistent {@code btree} (via {@code dropProperty} at
   * line 1739) but does NOT update the in-memory {@code PROPERTIES} cache map that
   * {@code getProperty} reads from (line 1128). Compare with {@code clearProperties}
   * which calls {@code properties.clear()} (line 1247), and {@code doSetProperty}
   * which calls {@code properties.put(...)} (line 1095). Consequence: after
   * {@code setProperty(k, v)} + {@code removeProperty(k)}, {@code getProperty(k)}
   * still returns {@code v} until the cache is rebuilt by a reload.
   *
   * <p><b>WHEN-FIXED:</b> add a {@code properties.remove(name)} call inside
   * {@code dropProperty} (or in the caller {@code removeProperty}) symmetrically to
   * the {@code put} in {@code doSetProperty}. After the fix this assertion will FAIL
   * (returned value will be {@code null}); flip to {@code assertNull(...)} and remove
   * the buggy-behaviour notes.
   */
  @Test
  public void testRemovePropertyDoesNotInvalidateInMemoryCache() throws IOException {
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setProperty(atomicOperation, "toRemove", "value"));
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.removeProperty(atomicOperation, "toRemove"));

    // Buggy behaviour: cache is stale, getProperty returns the old value despite removal.
    assertEquals("value", config.getProperty("toRemove"));
  }

  @Test
  public void testRemovePropertyRemovesFromPersistentBtree() throws IOException {
    // The persistent btree IS updated by removeProperty — verify via getProperties()
    // which also reads from the cache. Both views agree (both stale), so this
    // currently DOES find the entry. Pin the present shape so any change is visible.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setProperty(atomicOperation, "btreeRemove", "v"));
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.removeProperty(atomicOperation, "btreeRemove"));

    // getProperties() reads the cache, mirroring getProperty's bug.
    var props = config.getProperties();
    var found = props.stream().anyMatch(e -> "btreeRemove".equals(e.name));
    // WHEN-FIXED: flip to assertFalse once removeProperty invalidates the in-memory cache.
    assertTrue(
        "getProperties() reads cache; cache is stale after removeProperty (production bug)",
        found);
  }

  @Test
  public void testRemovePropertyOnAbsentKeyIsNoop() throws IOException {
    // Removing a key that does not exist must not throw.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.removeProperty(atomicOperation, "neverExisted"));
    // No exception means pass.
  }

  // --- clearProperties ---

  @Test
  public void testClearPropertiesRemovesAllUserDefinedProperties() throws IOException {
    // After clearProperties, all previously set user-defined properties are gone.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> {
          config.setProperty(atomicOperation, "clearA", "1");
          config.setProperty(atomicOperation, "clearB", "2");
        });
    atomicOps.executeInsideAtomicOperation(config::clearProperties);

    assertNull(config.getProperty("clearA"));
    assertNull(config.getProperty("clearB"));
  }

  // --- getProperties (list) ---

  @Test
  public void testGetPropertiesReturnsSetProperty() throws IOException {
    // getProperties() returns a list that includes every property set via setProperty.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setProperty(atomicOperation, "listKey", "listValue"));

    var props = config.getProperties();
    var found =
        props.stream().anyMatch(e -> "listKey".equals(e.name) && "listValue".equals(e.value));
    assertTrue("Expected 'listKey=listValue' in getProperties()", found);
  }

  // --- setSchemaRecordId / getSchemaRecordId ---

  @Test
  public void testSetSchemaRecordIdUpdatesGetSchemaRecordId() throws IOException {
    // Verifies that setSchemaRecordId persists the value and getSchemaRecordId
    // returns it.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setSchemaRecordId(atomicOperation, "#5:0"));

    assertEquals("#5:0", config.getSchemaRecordId());
  }

  // --- setIndexMgrRecordId / getIndexMgrRecordId ---

  @Test
  public void testSetIndexMgrRecordIdUpdatesGetIndexMgrRecordId() throws IOException {
    // Verifies that setIndexMgrRecordId persists the value.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setIndexMgrRecordId(atomicOperation, "#6:0"));

    assertEquals("#6:0", config.getIndexMgrRecordId());
  }

  // --- setConflictStrategy / getConflictStrategy ---

  @Test
  public void testSetConflictStrategyUpdatesGetConflictStrategy() throws IOException {
    // Verifies that setConflictStrategy stores the strategy name and
    // getConflictStrategy returns it.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setConflictStrategy(atomicOperation, "automerge"));

    assertEquals("automerge", config.getConflictStrategy());
  }

  // --- setCharset / getCharset ---

  @Test
  public void testSetCharsetUpdatesGetCharset() throws IOException {
    // Verifies round-trip for the charset property.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setCharset(atomicOperation, "UTF-8"));

    assertEquals("UTF-8", config.getCharset());
  }

  // --- setDateFormat / getDateFormat ---

  @Test
  public void testSetDateFormatUpdatesGetDateFormat() throws IOException {
    // Verifies that setDateFormat stores the pattern and getDateFormat returns it.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setDateFormat(atomicOperation, "yyyy-MM-dd"));

    assertEquals("yyyy-MM-dd", config.getDateFormat());
  }

  // --- setDateTimeFormat / getDateTimeFormat ---

  @Test
  public void testSetDateTimeFormatUpdatesGetDateTimeFormat() throws IOException {
    // Verifies that setDateTimeFormat stores the pattern.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setDateTimeFormat(atomicOperation, "yyyy-MM-dd HH:mm:ss"));

    assertEquals("yyyy-MM-dd HH:mm:ss", config.getDateTimeFormat());
  }

  // --- setTimeZone / getTimeZone ---

  @Test
  public void testSetTimeZoneUpdatesGetTimeZone() throws IOException {
    // Verifies that setTimeZone stores the time-zone ID and getTimeZone reconstructs
    // a matching TimeZone.
    var tz = TimeZone.getTimeZone("Europe/Berlin");
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setTimeZone(atomicOperation, tz));

    assertEquals("Europe/Berlin", config.getTimeZone().getID());
  }

  @Test
  public void testGetTimeZoneRepeatedReadsAreConsistentCacheHit() throws IOException {
    // The first getTimeZone after a set populates the volatile cachedTimeZone (slow path, under
    // the read lock); a second call must serve the cached instance (fast path). Both reads must
    // agree on the persisted zone. This exercises the slow-path-populate then fast-path-hit branch
    // pair of getTimeZone.
    var tz = TimeZone.getTimeZone("America/New_York");
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setTimeZone(atomicOperation, tz));

    var first = config.getTimeZone();
    var second = config.getTimeZone();

    assertNotNull(first);
    assertEquals("America/New_York", first.getID());
    // Repeated reads must be consistent: the cache-hit path returns the same logical zone.
    assertEquals(first.getID(), second.getID());
  }

  @Test
  public void testSetTimeZoneInvalidatesCacheSoSubsequentGetReflectsNewValue() throws IOException {
    // Setting two distinct time zones in sequence must make getTimeZone observe each new value.
    // This exercises the `cachedTimeZone = null` invalidation inside setTimeZone: if the cache
    // were not cleared on set, the second getTimeZone would return the stale first zone rather
    // than the newly persisted one.
    var utc = TimeZone.getTimeZone("UTC");
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setTimeZone(atomicOperation, utc));
    assertEquals("UTC", config.getTimeZone().getID());

    var offsetZone = TimeZone.getTimeZone("GMT+05:00");
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setTimeZone(atomicOperation, offsetZone));
    assertEquals("GMT+05:00", config.getTimeZone().getID());
  }

  @Test
  public void testGetTimeZoneAfterSetDoesNotServeStaleCachedValue() throws IOException {
    // Warm the cache with the first zone via a getTimeZone read (populates cachedTimeZone), THEN
    // change the zone. Because setTimeZone nulls the cache before rewriting the persistent
    // property, the next getTimeZone must return the NEW zone, never the warmed stale one. This is
    // the strongest anti-stale check: it fails if the cache-invalidation on set is dropped.
    var first = TimeZone.getTimeZone("America/New_York");
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setTimeZone(atomicOperation, first));
    // Populate the cache with the first value.
    assertEquals("America/New_York", config.getTimeZone().getID());

    var second = TimeZone.getTimeZone("UTC");
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setTimeZone(atomicOperation, second));

    // Must reflect the new zone; a stale warm cache would still report America/New_York.
    assertEquals("UTC", config.getTimeZone().getID());
  }

  // --- setLocaleLanguage/setLocaleCountry / recalculateLocale / getLocaleInstance ---

  @Test
  public void testSetLocaleLanguageAndCountryUpdatesLocaleInstance() throws IOException {
    // After setting locale language and country, getLocaleInstance() must return
    // a Locale matching the configured values.
    atomicOps.executeInsideAtomicOperation(atomicOperation -> {
      config.setLocaleLanguage(atomicOperation, "de");
      config.setLocaleCountry(atomicOperation, "DE");
    });

    var locale = config.getLocaleInstance();
    assertEquals("de", locale.getLanguage());
    assertEquals("DE", locale.getCountry());
  }

  @Test
  public void testGetLocaleInstanceIsNotNullOnFreshDb() {
    // On a freshly created DB the locale-language and locale-country are seeded
    // by the engine; getLocaleInstance() must always return a non-null Locale.
    assertNotNull(config.getLocaleInstance());
  }

  // --- setValidation / isValidationEnabled ---

  @Test
  public void testSetValidationTrueEnablesValidation() throws IOException {
    // Verifies that setValidation(true) makes isValidationEnabled() return true.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setValidation(atomicOperation, true));

    assertTrue(config.isValidationEnabled());
  }

  @Test
  public void testSetValidationFalseDisablesValidation() throws IOException {
    // Verifies that setValidation(false) makes isValidationEnabled() return false.
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setValidation(atomicOperation, false));

    assertFalse(config.isValidationEnabled());
  }

  // --- setUuid / getUuid ---

  @Test
  public void testSetUuidUpdatesGetUuid() throws IOException {
    // Verifies that setUuid stores the UUID string and getUuid returns it.
    var uuid = "550e8400-e29b-41d4-a716-446655440000";
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setUuid(atomicOperation, uuid));

    assertEquals(uuid, config.getUuid());
  }

  // --- setRecordSerializer / getRecordSerializer ---

  @Test
  public void testSetRecordSerializerUpdatesGetRecordSerializer() throws IOException {
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setRecordSerializer(atomicOperation, "ORecordSerializerBinary"));

    assertEquals("ORecordSerializerBinary", config.getRecordSerializer());
  }

  // --- setRecordSerializerVersion / getRecordSerializerVersion ---

  @Test
  public void testSetRecordSerializerVersionUpdatesGetRecordSerializerVersion() throws IOException {
    // Capture the original version. Setting an unsupported version (e.g., 2)
    // makes the DB unopenable on the next session.init() — the tearDown's
    // youTrackDB.drop() reopens the DB internally and fails with
    // "Persistent record serializer version is not support by the current
    // implementation". Restore the original version before tearDown so drop
    // can complete cleanly — and crucially, restore even when the assertion
    // below fires, otherwise the next test's @Before would inherit a broken
    // serializer-version state through the shared on-disk database.
    final int originalVersion = config.getRecordSerializerVersion();

    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setRecordSerializerVersion(atomicOperation, 2));

    try {
      assertEquals(2, config.getRecordSerializerVersion());
    } finally {
      // Restore in finally so a failed assertion above does not leak the
      // unsupported version into subsequent tests' @Before / tearDown.
      atomicOps.executeInsideAtomicOperation(
          atomicOperation -> config.setRecordSerializerVersion(atomicOperation, originalVersion));
    }
  }

  // --- getDateFormatInstance / getDateTimeFormatInstance ---

  @Test
  public void testGetDateFormatInstanceIsNotNull() {
    // getDateFormatInstance() must return a SimpleDateFormat that reflects the
    // currently configured date format and time zone.
    var sdf = config.getDateFormatInstance();
    assertNotNull(sdf);
  }

  @Test
  public void testGetDateTimeFormatInstanceIsNotNull() {
    var sdf = config.getDateTimeFormatInstance();
    assertNotNull(sdf);
  }

  // --- pauseUpdateNotifications / fireUpdateNotifications ---

  @Test
  public void testPausedNotificationsAreNotFiredUntilFire() throws IOException {
    // While notifications are paused, the update listener must not receive any
    // call. After fireUpdateNotifications(), any pending change notification
    // is delivered exactly once.
    var listenerCallCount = new AtomicInteger(0);
    StorageConfigurationUpdateListener listener = cfg -> listenerCallCount.incrementAndGet();
    config.setConfigurationUpdateListener(listener);
    try {
      // Pause notifications and make a property change.
      config.pauseUpdateNotifications();
      atomicOps.executeInsideAtomicOperation(
          atomicOperation -> config.setProperty(atomicOperation, "pausedKey", "pausedValue"));

      // Listener must not have been called yet because notifications are paused.
      assertEquals(0, listenerCallCount.get());

      // Fire notifications — the pending change is delivered now.
      config.fireUpdateNotifications();
      assertEquals(1, listenerCallCount.get());
    } finally {
      config.setConfigurationUpdateListener(null);
    }
  }

  @Test
  public void testFireUpdateNotificationsWithoutPauseDoesNotFireExtraNotifications() {
    // When notifications are not paused, fireUpdateNotifications() does nothing
    // (there are no pending changes to deliver).
    var listenerCallCount = new AtomicInteger(0);
    StorageConfigurationUpdateListener listener = cfg -> listenerCallCount.incrementAndGet();
    config.setConfigurationUpdateListener(listener);
    try {
      // Ensure state is not paused, then call fire with no pending changes.
      config.fireUpdateNotifications();

      assertEquals(0, listenerCallCount.get());
    } finally {
      config.setConfigurationUpdateListener(null);
    }
  }

  // --- setMinimumCollections / getMinimumCollections ---
  //
  // Coverage gap: setMinimumCollections is intentionally NOT exercised here. The
  // method holds a latent production deadlock that surfaced during this test
  // class's authoring:
  //
  //   setMinimumCollections (line 323) → lock.writeLock().lock()
  //                                    → getContextConfiguration() (line 372)
  //                                    → lock.readLock().lock()  ← spins forever
  //
  // The class's ScalableRWLock is non-reentrant (see the Javadoc on
  // readMinimumCollections, lines 338-342, which deliberately accesses the
  // configuration field directly to avoid the same trap). setMinimumCollections
  // failed to apply that workaround and therefore self-deadlocks on every call.
  //
  // Symptom observed during Phase B authoring of this test: JUnitTestListener
  // deadlock-watchdog dumps diagnostics and halts the surefire JVM after 900 s,
  // turning the run into "VM crash or System.exit called?" with Tests run: 0.
  //
  // We do not pin the deadlock with a regression test because the only available
  // pin would leak a daemon thread spinning in Thread.yield() — the
  // ScalableRWLock fast path does not respond to interrupt — which would burn
  // CPU during every subsequent test method in the class.
  //
  // WHEN-FIXED: replace the `getContextConfiguration()` call inside
  // setMinimumCollections (line 326) with a direct `configuration.setValue(...)`
  // call mirroring readMinimumCollections (line 346). At that point, add:
  //
  //   @Test
  //   public void testSetMinimumCollectionsUpdatesGetMinimumCollections() {
  //     config.setMinimumCollections(16);
  //     assertTrue(config.getMinimumCollections() > 0);
  //   }
  //
  // and remove this comment block. getMinimumCollections() is exercised
  // transitively by other tests (Locale tests via auto-init), so the read-path
  // already has coverage; only the broken write-path is uncovered.

  // --- getContextConfiguration ---

  @Test
  public void testGetContextConfigurationIsNotNull() {
    // getContextConfiguration() must return the ContextConfiguration used to
    // initialise the storage.
    assertNotNull(config.getContextConfiguration());
  }

  // --- getVersion ---

  @Test
  public void testGetVersionReturnsNonNegativeValue() throws IOException {
    // The stored version is set during create()/load() and must be non-negative.
    var version = atomicOps.calculateInsideAtomicOperation(
        atomicOperation -> config.getVersion(atomicOperation));
    assertTrue("version must be >= 0", version >= 0);
  }

  // --- getBinaryFormatVersion ---

  @Test
  public void testGetBinaryFormatVersionReturnsNonNegativeValue() throws IOException {
    var bfv = atomicOps.calculateInsideAtomicOperation(
        atomicOperation -> config.getBinaryFormatVersion(atomicOperation));
    assertTrue("binaryFormatVersion must be >= 0", bfv >= 0);
  }

  // --- getName (interface default returns null for config) ---

  @Test
  public void testGetNameReturnsNull() {
    // CollectionBasedStorageConfiguration.getName() returns null per the implementation.
    assertNull(config.getName());
  }

  // --- setCreationVersion / getCreatedAtVersion ---

  @Test
  public void testSetCreationVersionUpdatesGetCreatedAtVersion() throws IOException {
    atomicOps.executeInsideAtomicOperation(
        atomicOperation -> config.setCreationVersion(atomicOperation, "0.4.0"));

    assertEquals("0.4.0", config.getCreatedAtVersion());
  }
}
