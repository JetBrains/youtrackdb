package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link ImmutableSecurityPolicy} — both the copy-from-{@link SecurityPolicy}
 * constructor and the all-args constructor used by in-memory snapshots.
 */
public class ImmutableSecurityPolicyTest extends DbTestBase {

  /**
   * Roll back any transaction left open by a failing test before the database is dropped.
   * JUnit 4 runs subclass {@code @After} methods before superclass ones, so this fires
   * ahead of the database teardown. Carry-forward convention from Tracks 8–16.
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  // ─── Direct all-args constructor ─────────────────────────────────────────

  /**
   * Verifies that the six-arg constructor stores every rule field and always
   * reports {@code isActive() == true} (the in-memory snapshot is always active).
   */
  @Test
  public void testAllArgsConstructorStoresFieldsAndIsAlwaysActive() {
    var policy = new ImmutableSecurityPolicy(
        "snap",
        "1 = 1", // create
        "name = 'x'", // read
        "true", // beforeUpdate
        "false", // afterUpdate
        "1 = 2", // delete
        "true" // execute
    );

    Assert.assertEquals("snap", policy.getName());
    Assert.assertTrue("in-memory snapshot must always be active", policy.isActive());
    Assert.assertEquals("1 = 1", policy.getCreateRule());
    Assert.assertEquals("name = 'x'", policy.getReadRule());
    Assert.assertEquals("true", policy.getBeforeUpdateRule());
    Assert.assertEquals("false", policy.getAfterUpdateRule());
    Assert.assertEquals("1 = 2", policy.getDeleteRule());
    Assert.assertEquals("true", policy.getExecuteRule());
    // Identity is a synthetic RecordId(-1, -1) — must be non-null
    Assert.assertNotNull(policy.getIdentity());
  }

  /**
   * Verifies that null rule fields are preserved faithfully in the all-args constructor.
   */
  @Test
  public void testAllArgsConstructorAllowsNullRuleFields() {
    var policy = new ImmutableSecurityPolicy("empty", null, null, null, null, null, null);

    Assert.assertEquals("empty", policy.getName());
    Assert.assertNull(policy.getCreateRule());
    Assert.assertNull(policy.getReadRule());
    Assert.assertNull(policy.getBeforeUpdateRule());
    Assert.assertNull(policy.getAfterUpdateRule());
    Assert.assertNull(policy.getDeleteRule());
    Assert.assertNull(policy.getExecuteRule());
  }

  // ─── Copy-from-SecurityPolicy constructor ────────────────────────────────

  /**
   * Verifies that the copy constructor faithfully snapshots every field from a live
   * {@link SecurityPolicyImpl} created in a database transaction.
   */
  @Test
  public void testCopyConstructorSnapshotsAllFieldsFromLivePolicy() {
    var security = session.getSharedContext().getSecurity();

    session.begin();
    var live = security.createSecurityPolicy(session, "copyTest");
    live.setActive(true);
    live.setCreateRule("1 = 1");
    live.setReadRule("name = 'read'");
    live.setBeforeUpdateRule("name = 'beforeUpdate'");
    live.setAfterUpdateRule("name = 'afterUpdate'");
    live.setDeleteRule("name = 'delete'");
    live.setExecuteRule("name = 'execute'");
    security.saveSecurityPolicy(session, live);
    session.commit();

    // Create an immutable snapshot from the live policy
    var snapshot = new ImmutableSecurityPolicy(live);

    Assert.assertEquals("copyTest", snapshot.getName());
    Assert.assertTrue(snapshot.isActive());
    Assert.assertEquals("1 = 1", snapshot.getCreateRule());
    Assert.assertEquals("name = 'read'", snapshot.getReadRule());
    Assert.assertEquals("name = 'beforeUpdate'", snapshot.getBeforeUpdateRule());
    Assert.assertEquals("name = 'afterUpdate'", snapshot.getAfterUpdateRule());
    Assert.assertEquals("name = 'delete'", snapshot.getDeleteRule());
    Assert.assertEquals("name = 'execute'", snapshot.getExecuteRule());
    // Identity must be copied from the live policy
    Assert.assertEquals(live.getIdentity(), snapshot.getIdentity());
  }

  /**
   * Verifies that modifying the live policy after snapshot creation does NOT affect
   * the snapshot (immutability guarantee).
   */
  @Test
  public void testSnapshotIsImmutableAfterSourceModified() {
    var security = session.getSharedContext().getSecurity();

    session.begin();
    var live = security.createSecurityPolicy(session, "immutTest");
    live.setActive(true);
    live.setReadRule("name = 'original'");
    security.saveSecurityPolicy(session, live);
    session.commit();

    var snapshot = new ImmutableSecurityPolicy(live);
    Assert.assertEquals("name = 'original'", snapshot.getReadRule());

    // Mutate the live policy (in a new transaction)
    session.begin();
    live.setReadRule("name = 'changed'");
    security.saveSecurityPolicy(session, live);
    session.commit();

    // Snapshot must still hold the original value
    Assert.assertEquals("name = 'original'", snapshot.getReadRule());
  }

  /**
   * Verifies that an inactive live policy is correctly captured with {@code isActive() == false}
   * in the snapshot.
   */
  @Test
  public void testCopyConstructorPreservesInactiveFlag() {
    var security = session.getSharedContext().getSecurity();

    session.begin();
    var live = security.createSecurityPolicy(session, "inactiveTest");
    live.setActive(false);
    security.saveSecurityPolicy(session, live);
    session.commit();

    var snapshot = new ImmutableSecurityPolicy(live);
    Assert.assertFalse(snapshot.isActive());
  }
}
