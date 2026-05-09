/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.conflict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests for the {@code core/conflict} cluster — the
 * {@link RecordConflictStrategyFactory} that registers the three built-in MVCC
 * conflict strategies, plus the strategies themselves:
 * {@link VersionRecordConflictStrategy} (the default — throws on version mismatch),
 * {@link AutoMergeRecordConflictStrategy} (placeholder that throws
 * {@link UnsupportedOperationException}), and
 * {@link ContentRecordConflictStrategy} (likewise unimplemented). The strategies
 * are pure objects (no DB session required); the {@code Storage} parameter on the
 * version strategy is consulted only for {@code getName} when constructing the
 * exception message, so a Mockito stub suffices.
 */
public class RecordConflictStrategyFactoryTest {

  // ---------------------------------------------------------------------------
  // Factory — registers all three built-ins and exposes the version default
  // ---------------------------------------------------------------------------

  @Test
  public void factoryRegistersVersionAutoMergeAndContentStrategiesUnderTheirCanonicalNames() {
    var factory = new RecordConflictStrategyFactory();
    var version = factory.getStrategy(VersionRecordConflictStrategy.NAME);
    var autoMerge = factory.getStrategy(AutoMergeRecordConflictStrategy.NAME);
    var content = factory.getStrategy(ContentRecordConflictStrategy.NAME);

    assertNotNull("version strategy must be registered", version);
    assertNotNull("automerge strategy must be registered", autoMerge);
    assertNotNull("content strategy must be registered", content);

    assertTrue(version instanceof VersionRecordConflictStrategy);
    assertTrue(autoMerge instanceof AutoMergeRecordConflictStrategy);
    assertTrue(content instanceof ContentRecordConflictStrategy);
  }

  @Test
  public void factoryReturnsTheVersionStrategyAsTheDefaultImplementation() {
    var factory = new RecordConflictStrategyFactory();
    // getDefaultStrategy returns the canonical name; getDefaultImplementation
    // (inherited from ConfigurableStatelessFactory) returns the actual instance.
    assertEquals(VersionRecordConflictStrategy.NAME, factory.getDefaultStrategy());
    assertTrue("default implementation must be the version strategy",
        factory.getDefaultImplementation() instanceof VersionRecordConflictStrategy);
  }

  @Test
  public void factoryReusesTheSameVersionStrategyInstanceForDefaultAndForVersionLookup() {
    // The constructor passes a single VersionRecordConflictStrategy to both
    // registerImplementation(NAME, def) and setDefaultImplementation(def). A
    // refactor that allocated a second instance for the default would silently
    // change identity here; pin with assertSame.
    var factory = new RecordConflictStrategyFactory();
    var fromGet = factory.getStrategy(VersionRecordConflictStrategy.NAME);
    var fromDefault = factory.getDefaultImplementation();
    assertSame("default implementation must alias the version strategy registered by name",
        fromGet, fromDefault);
  }

  @Test
  public void factoryReturnsNullForUnknownNonNullName() {
    // ConfigurableStatelessFactory#getImplementation returns null for an unknown
    // key (only null keys take the defaultImplementation branch). Pin the
    // null-on-miss behaviour — the version strategy is NOT a fallback for
    // unknown names; callers must check the lookup result explicitly.
    var factory = new RecordConflictStrategyFactory();
    assertNull("unknown non-null names must not fall back to the default",
        factory.getStrategy("not-a-strategy"));
  }

  @Test
  public void factoryReturnsTheDefaultImplementationOnNullName() {
    // The null-key branch of getImplementation returns the configured default
    // — for the conflict factory that is the version strategy.
    var factory = new RecordConflictStrategyFactory();
    var fallback = factory.getStrategy(null);
    assertNotNull("null name falls back to default", fallback);
    assertTrue("null key returns the default implementation (version)",
        fallback instanceof VersionRecordConflictStrategy);
  }

  // ---------------------------------------------------------------------------
  // VersionRecordConflictStrategy — throws on every onUpdate, surfaces NAME
  // ---------------------------------------------------------------------------

  @Test
  public void versionStrategyGetNameReturnsTheLiteralVersionConstant() {
    assertEquals("version", VersionRecordConflictStrategy.NAME);
    assertEquals("version", new VersionRecordConflictStrategy().getName());
  }

  @Test
  public void versionStrategyOnUpdateThrowsConcurrentModificationExceptionWithDbName() {
    // The version strategy's checkVersions always throws ConcurrentModificationException
    // — there is no "versions match" branch (a no-op match is encoded by the caller
    // not invoking the strategy at all). Pin the throw and the exception message
    // includes the db name from storage.getName().
    var storage = Mockito.mock(Storage.class);
    Mockito.when(storage.getName()).thenReturn("test-db");
    var strategy = new VersionRecordConflictStrategy();
    var rid = new RecordId(3, 7L);
    var dbVersion = new AtomicInteger(5);
    try {
      strategy.onUpdate(storage, (byte) 'd', rid, /*record version*/ 4, new byte[0], dbVersion);
      fail("VersionRecordConflictStrategy.onUpdate must throw on every invocation");
    } catch (ConcurrentModificationException expected) {
      // The exception carries the rid, the version pair, and the db name used in
      // audit logs; pin each so a refactor that drops any argument is loud.
      assertEquals("exception carries the originating rid", rid, expected.getRid());
      assertEquals("exception carries the database version",
          dbVersion.get(), expected.getEnhancedDatabaseVersion());
      assertEquals("exception carries the record version",
          4, expected.getEnhancedRecordVersion());
      assertEquals("exception carries the db name from storage.getName()",
          "test-db", expected.getDbName());
    }
  }

  // ---------------------------------------------------------------------------
  // AutoMergeRecordConflictStrategy — placeholder, every onUpdate throws UOE
  // ---------------------------------------------------------------------------

  @Test
  public void autoMergeStrategyGetNameReturnsTheLiteralAutomergeConstant() {
    assertEquals("automerge", AutoMergeRecordConflictStrategy.NAME);
    assertEquals("automerge", new AutoMergeRecordConflictStrategy().getName());
  }

  @Test
  public void autoMergeStrategyOnUpdateThrowsUnsupportedOperationException() {
    // The automerge strategy is registered for compatibility with prior on-disk
    // schema configs, but its execution path is not implemented in this build —
    // pin the explicit UnsupportedOperationException so a future "real" implementation
    // surfaces here.
    var strategy = new AutoMergeRecordConflictStrategy();
    var rid = new RecordId(0, 1L);
    try {
      strategy.onUpdate(/*storage*/ null, (byte) 'd', rid, 0,
          new byte[0], new AtomicInteger(0));
      fail("AutoMergeRecordConflictStrategy must throw UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
      // expected
    }
  }

  // ---------------------------------------------------------------------------
  // ContentRecordConflictStrategy — placeholder, every onUpdate throws UOE
  // ---------------------------------------------------------------------------

  @Test
  public void contentStrategyGetNameReturnsTheLiteralContentConstant() {
    assertEquals("content", ContentRecordConflictStrategy.NAME);
    assertEquals("content", new ContentRecordConflictStrategy().getName());
  }

  @Test
  public void contentStrategyOnUpdateThrowsUnsupportedOperationException() {
    var strategy = new ContentRecordConflictStrategy();
    var rid = new RecordId(0, 1L);
    try {
      strategy.onUpdate(/*storage*/ null, (byte) 'd', rid, 0,
          new byte[0], new AtomicInteger(0));
      fail("ContentRecordConflictStrategy must throw UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
      // expected
    }
  }

  // ---------------------------------------------------------------------------
  // ConfigurableStatelessFactory surface (inherited) — register / unregister
  // ---------------------------------------------------------------------------

  @Test
  public void factoryUnregisterImplementationRemovesTheNamedStrategy() {
    // The factory inherits register/unregister from ConfigurableStatelessFactory.
    // After unregister, the name is gone from the registry view AND lookups for
    // that name return null (no default-fallback for non-null unknown keys).
    var factory = new RecordConflictStrategyFactory();
    factory.unregisterImplementation(AutoMergeRecordConflictStrategy.NAME);
    assertTrue("registered names must omit the unregistered strategy",
        !factory.getRegisteredImplementationNames().contains(
            AutoMergeRecordConflictStrategy.NAME));
    assertNull("unregistered non-null name returns null on lookup",
        factory.getStrategy(AutoMergeRecordConflictStrategy.NAME));
  }

  @Test
  public void factoryRegisterImplementationAcceptsACustomStrategy() {
    // Pin the inherited register path: registering a custom strategy under a new
    // name routes the lookup to that strategy.
    var factory = new RecordConflictStrategyFactory();
    var custom = new VersionRecordConflictStrategy() {
      @Override
      public String getName() {
        return "custom-name";
      }
    };
    factory.registerImplementation("custom-name", custom);
    assertSame(custom, factory.getStrategy("custom-name"));
  }

  @Test
  public void factoryGetImplementationOnEmptyFactoryReturnsNull() {
    // Unregister everything and clear the default — getImplementation returns null
    // on miss when no default is set.
    var factory = new RecordConflictStrategyFactory();
    factory.unregisterAllImplementations();
    factory.setDefaultImplementation(null);
    assertNull("empty factory with no default returns null on lookup",
        factory.getStrategy(VersionRecordConflictStrategy.NAME));
  }
}
