package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Arrays;
import org.junit.Test;

/**
 * Coverage for the tx-local schema view foundation: {@link SchemaShared#copyForTx} (the
 * serialize-then-re-parse seed) and {@link TxSchemaState} (the per-transaction holder). These are
 * the per-session, copy-on-first-write primitives a schema transaction mutates in isolation. Routing, de-guarding, and commit-time
 * promotion are later steps; here the copy is built directly and inspected, never promoted.
 */
public class CopyForTxTest extends DbTestBase {

  private SchemaShared committedSchema() {
    return session.getSharedContext().getSchema();
  }

  /**
   * copyForTx returns a fresh SchemaShared distinct from the committed instance, and the copy carries
   * the committed root identity so a later commit serializes back to the same root record. The copy
   * is the promotion target's source, so it must not be the shared instance itself.
   */
  @Test
  public void copyIsAFreshInstanceCarryingTheCommittedIdentity() {
    var committed = committedSchema();
    var committedIdentity = committed.getIdentity();

    var copy = session.computeInTx(tx -> committed.copyForTx(session));

    assertNotSame("copyForTx must not hand back the committed shared instance", committed, copy);
    assertEquals(
        "the copy must carry the committed root identity so commit targets the same record",
        committedIdentity, copy.getIdentity());
  }

  /**
   * A committed class round-trips into the copy with its per-class record RID preserved, and the
   * copy's class object is a fresh instance whose {@code owner} is the copy, not the committed
   * {@link SchemaShared}. Re-binding ownership is the whole reason the seed is a re-parse and not a
   * field clone: a clone would leave each class pointing at the shared owner, so a later
   * tx-local mutation would ripple into the committed graph. Mutation entry points are de-guarded in
   * a later step, so isolation is verified here by the object-graph disjointness the re-parse
   * establishes, not by running a DDL mutation through the still-guarded path.
   */
  @Test
  public void classRoundTripsWithRidPreservedAndOwnerReboundToTheCopy() {
    session.getMetadata().getSchema().createClass("Isolated");
    var committed = committedSchema();
    var committedCls = committed.getClass("Isolated");
    var committedRid = committedCls.getRecordId();
    assertNotNull("a committed class must carry its per-class record RID", committedRid);
    assertTrue("the committed RID must be persistent", committedRid.isPersistent());

    var copy = session.computeInTx(tx -> committed.copyForTx(session));

    var copiedCls = copy.getClass("Isolated");
    assertNotNull("the class must survive the re-parse into the copy", copiedCls);
    assertNotSame("the copy's class must be a fresh object, not the committed instance",
        committedCls, copiedCls);
    assertEquals("the per-class record RID must be preserved through the round trip",
        committedRid, copiedCls.getRecordId());
    assertSame("the re-parse must rebind the class's owner to the copy, not the committed instance",
        copy, copiedCls.getOwner());
    assertSame("the committed class must keep pointing at the committed instance",
        committed, committedCls.getOwner());
  }

  /**
   * The cross-class derived state a schema write recomputes — inheritance links, the subclass set,
   * and each class's polymorphic collection ids — is rebuilt correctly through the re-parse, with all
   * the links bound to the copy's own class objects rather than the committed ones. This is why the
   * copy is a re-parse and not a field clone: a clone would leave the links pointing at the
   * shared classes.
   */
  @Test
  public void inheritanceAndPolymorphicCollectionsAreRecomputedInTheCopy() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Parent");
    schema.createClass("Child", schema.getClass("Parent"));

    var committed = committedSchema();
    var committedParent = committed.getClass("Parent");
    var committedChild = committed.getClass("Child");
    var childCollectionId = committedChild.getCollectionIds()[0];
    assertTrue("a non-abstract child must own a real collection id", childCollectionId >= 0);
    assertTrue("the committed parent's polymorphic ids must already include the child's collection",
        Arrays.stream(committedParent.getPolymorphicCollectionIds())
            .anyMatch(id -> id == childCollectionId));

    var copy = session.computeInTx(tx -> committed.copyForTx(session));

    var copiedParent = copy.getClass("Parent");
    var copiedChild = copy.getClass("Child");
    assertNotNull(copiedParent);
    assertNotNull(copiedChild);

    // The child's superclass link must resolve to the COPY's parent object, not the committed one.
    assertEquals("the child must have exactly one superclass after the re-parse",
        1, copiedChild.getSuperClasses().size());
    assertSame("the child's superclass must be bound to the copy's parent object",
        copiedParent, copiedChild.getSuperClasses().get(0));

    // The subclass set is the reverse link; it too must be bound to the copy's child object.
    assertTrue("the copy's parent must list the copy's child as a subclass",
        copiedParent.getSubclasses().contains(copiedChild));

    // polymorphicCollectionIds is the recomputed union; the copy's parent must include the child's
    // collection id, proving the derived-state ripple ran inside the copy.
    assertTrue("the copy's parent must recompute the polymorphic union over the copy's hierarchy",
        Arrays.stream(copiedParent.getPolymorphicCollectionIds())
            .anyMatch(id -> id == childCollectionId));
  }

  /**
   * TxSchemaState holds the copy handed to it and records changed class names idempotently. The
   * holder is the per-transaction container the routing and commit steps consume; here it is
   * inspected directly.
   */
  @Test
  public void txSchemaStateHoldsCopyAndRecordsChangedClassesIdempotently() {
    var committed = committedSchema();
    var copy = session.computeInTx(tx -> committed.copyForTx(session));

    var state = new TxSchemaState(copy);
    assertSame("the holder must return the copy it was seeded with",
        copy, state.getTxLocalSchema());
    assertTrue("a fresh holder records no changed classes", state.getChangedClasses().isEmpty());

    state.markClassChanged("Alpha");
    state.markClassChanged("Beta");
    state.markClassChanged("Alpha");
    assertEquals("recording the same class twice must not duplicate it",
        2, state.getChangedClasses().size());
    assertTrue(state.getChangedClasses().contains("Alpha"));
    assertTrue(state.getChangedClasses().contains("Beta"));
  }

  /**
   * Seeding the copy is read-only with respect to committed schema state: it must not enrol the
   * committed root record or any committed per-class record into the caller's transaction. The seed
   * reads the committed root record and re-parses it rather than re-serializing through {@code
   * toStream} (which is a writer that dirties the committed records and rebinds class RIDs), so a
   * transaction that did nothing but build the copy still has an empty change set. Were the seed to
   * dirty the committed records, a user transaction that made no schema change would re-persist every
   * class record at commit; this test pins that side effect out.
   */
  @Test
  public void seedDoesNotEnrolCommittedRecordsIntoTheCallerTransaction() {
    // Persist a couple of committed classes (each owns a standalone per-class record on disk) so the
    // seed has real committed records it could dirty if it re-serialized them.
    var schema = session.getMetadata().getSchema();
    schema.createClass("Untouched");
    schema.createClass("AlsoUntouched");
    var committed = committedSchema();

    // Build the copy inside a fresh transaction and read the change set back from inside that same
    // transaction, before computeInTx commits it.
    int changeSetSizeAfterSeed =
        session.computeInTx(
            tx -> {
              // The transaction starts clean.
              assertEquals(
                  "a freshly opened transaction must have no record operations",
                  0, session.getTransactionInternal().getEntryCount());
              committed.copyForTx(session);
              return session.getTransactionInternal().getEntryCount();
            });

    assertEquals(
        "building the tx-local copy must not enrol any committed schema record as dirty in the"
            + " caller's transaction",
        0, changeSetSizeAfterSeed);
  }

  /**
   * Building the copy must not rebind any committed class's per-class record id. The legacy {@code
   * toStream} serializer rebinds a committed class's RID for any class whose record id is null or
   * non-persistent; the read-only seed must never touch the committed class objects, so a committed
   * class keeps the exact same RID object before and after a copy is built.
   */
  @Test
  public void seedDoesNotRebindCommittedClassRecordIds() {
    session.getMetadata().getSchema().createClass("Stable");
    var committed = committedSchema();
    var committedCls = committed.getClass("Stable");
    var ridBeforeSeed = committedCls.getRecordId();
    assertNotNull("a committed class must carry its per-class record RID", ridBeforeSeed);

    session.computeInTx(tx -> committed.copyForTx(session));

    assertSame(
        "the seed must not rebind the committed class's record id object",
        ridBeforeSeed, committed.getClass("Stable").getRecordId());
  }

  /**
   * The copy must not share a mutable record-id reference with the committed instance: a later
   * in-place promotion of one id must not be observable through the other. The committed schema root
   * id is persistent (hence immutable), so the value-copy carries an equal, persistent id that cannot
   * be mutated to corrupt the committed instance. This pins the value-copy of the identity rather
   * than the previous reference aliasing.
   */
  @Test
  public void copyCarriesAnImmutableValueCopyOfTheCommittedRootIdentity() {
    var committed = committedSchema();
    var committedIdentity = committed.getIdentity();
    assertTrue(
        "the committed root identity must be persistent before any copy is built",
        committedIdentity.isPersistent());

    var copy = session.computeInTx(tx -> committed.copyForTx(session));

    assertEquals(
        "the copy must carry the committed root identity by value",
        committedIdentity, copy.getIdentity());
    assertTrue(
        "the copy's identity must be persistent (immutable), so no in-place promotion can leak"
            + " between the committed instance and the copy",
        copy.getIdentity().isPersistent());
  }
}
