package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

/**
 * Coverage for the three-tier proxy routing seam ({@link SchemaProxedResource}) and its
 * transaction-scoped state hooks ({@code DatabaseSessionEmbedded#getTxSchemaState} /
 * {@code ensureTxSchemaState}). The seam decides, per call, whether a schema proxy operates on the
 * committed shared schema (tier 2) or on the transaction's private tx-local copy (tier 3), and
 * re-resolves impl-typed arguments by name so a committed-shared object never leaks into the private
 * graph.
 *
 * <p>The de-guarding of the mutation entry points (so an in-transaction DDL write succeeds against
 * the tx-local copy instead of throwing) and the commit-time promotion are later steps. These tests
 * therefore verify the routing decision and the seeding mechanics in isolation: classes are created
 * at the top level (the committed path that works today), then the seam's resolution is observed
 * through the object graph rather than by running a still-guarded in-transaction mutation.
 */
public class SchemaProxyRoutingTest extends DbTestBase {

  private SchemaShared committedSchema() {
    return session.getSharedContext().getSchema();
  }

  /**
   * Tier 2: with no schema write-view seeded for the transaction, a proxy read resolves against the
   * committed shared instance, so the class object a read hands back is the committed one. This is
   * the unchanged pre-seam behaviour and the common case (no schema transaction in progress).
   */
  @Test
  public void readResolvesToCommittedWhenNoWriteViewSeeded() {
    session.getMetadata().getSchema().createClass("Tier2");
    var committed = committedSchema();

    session.executeInTx(
        tx -> {
          // No ensureTxSchemaState call, so getTxSchemaState stays null and resolve() is tier 2.
          assertNull("no write-view must be seeded by a pure read",
              session.getTxSchemaState());
          var cls =
              (SchemaClassInternal) session.getMetadata().getSchema().getClass("Tier2");
          assertSame(
              "without a write-view the proxy read must resolve to the committed class object",
              committed, cls.getImplementation().getOwner());
        });
  }

  /**
   * Tier 3: once a write-view is seeded for the transaction, a proxy read re-resolves the class by
   * name into the tx-local copy, so the class object the read hands back is owned by the copy, not
   * the committed instance. The class is created at the top level first so both schemas contain it;
   * the test then proves the read routed to the copy by inspecting the resolved object's owner.
   */
  @Test
  public void readResolvesToTxLocalCopyOnceWriteViewSeeded() {
    session.getMetadata().getSchema().createClass("Tier3");
    var committed = committedSchema();

    session.executeInTx(
        tx -> {
          var state = session.ensureTxSchemaState();
          var copy = state.getTxLocalSchema();
          assertNotSame("the seeded copy must not be the committed instance", committed, copy);

          var cls =
              (SchemaClassInternal) session.getMetadata().getSchema().getClass("Tier3");
          assertSame(
              "with a write-view seeded the proxy read must resolve into the tx-local copy",
              copy, cls.getImplementation().getOwner());
          assertNotSame(
              "the resolved class must be the copy's object, not the committed one",
              committed.getClass("Tier3"), cls.getImplementation());
        });
  }

  /**
   * The write-view is transaction-scoped: it is null outside any transaction and is seeded at most
   * once per transaction. A second seed call inside the same transaction returns the same state
   * object, proving the copy is built once and reused rather than rebuilt on every routed write.
   */
  @Test
  public void writeViewIsTransactionScopedAndSeededOnce() {
    assertNull("outside a transaction there is no tx-local schema state",
        session.getTxSchemaState());

    session.executeInTx(
        tx -> {
          assertNull("a freshly opened transaction has no write-view until the first write",
              session.getTxSchemaState());
          var first = session.ensureTxSchemaState();
          var second = session.ensureTxSchemaState();
          assertSame("the write-view must be seeded once and reused within the transaction",
              first, second);
          assertSame("getTxSchemaState must return the seeded state",
              first, session.getTxSchemaState());
        });

    assertNull("the write-view must not survive the transaction",
        session.getTxSchemaState());
  }

  /**
   * The proxy's write seam seeds the tx-local copy and resolves into it. {@code resolveForWrite} is
   * the helper every proxy write method routes through; calling it directly inside a transaction
   * (without running a still-guarded DDL mutation, which would only throw in this step) proves it
   * seeds the write-view and hands back the copy, while a read seam ({@code resolve}) called first
   * does not seed. The de-guarding that lets the mutation itself succeed against the copy is a later
   * step.
   */
  @Test
  public void proxyWriteSeamSeedsAndResolvesIntoTheCopy() {
    session.executeInTx(
        tx -> {
          var proxy = session.getMetadata().getSchema();

          // A read seam call must not seed a write-view.
          proxy.resolve();
          assertNull("a read through the seam must not seed a write-view",
              session.getTxSchemaState());

          // The write seam seeds the copy and resolves to it.
          var resolvedForWrite = proxy.resolveForWrite();
          var state = session.getTxSchemaState();
          assertTrue("the write seam must have seeded a tx-local copy", state != null);
          assertSame("the write seam must resolve into the seeded tx-local copy",
              state.getTxLocalSchema(), resolvedForWrite);
          assertNotSame("the write seam must not resolve to the committed instance",
              committedSchema(), resolvedForWrite);
        });
  }

  /**
   * Outside a transaction a routed write keeps the legacy top-level path: it does not seed a
   * tx-local copy (there would be no transaction to defer its commit to) and the create succeeds
   * against the committed schema, exactly as before the seam existed.
   */
  @Test
  public void writeOutsideTransactionKeepsLegacyTopLevelPath() {
    session.getMetadata().getSchema().createClass("LegacyTopLevel");

    assertTrue("a top-level create must land in the committed schema",
        committedSchema().existsClass("LegacyTopLevel"));
    assertNull("a top-level write must not seed a tx-local copy",
        session.getTxSchemaState());
  }

  /**
   * Impl-argument re-resolution maps a committed-shared class object to the same-named class in the
   * tx-local copy, so a write that links a superclass or linked class never pulls a shared object
   * into the private graph. A class already owned by the copy is returned unchanged, and a null
   * argument stays null.
   */
  @Test
  public void reresolveClassImplMapsCommittedClassIntoTheCopy() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Linkable");
    var committed = committedSchema();
    var committedImpl = committed.getClass("Linkable");

    var copy = session.computeInTx(tx -> committed.copyForTx(session));
    var copyImpl = copy.getClass("Linkable");

    var resolved = SchemaProxedResource.reresolveClassImpl(copy, committedImpl);
    assertSame("a committed class impl must re-resolve to the copy's same-named class",
        copyImpl, resolved);
    assertSame("re-resolution must bind the result to the copy",
        copy, resolved.getOwner());

    assertSame("a class already owned by the copy must be returned unchanged",
        copyImpl, SchemaProxedResource.reresolveClassImpl(copy, copyImpl));

    assertNull("a null class argument must stay null",
        SchemaProxedResource.reresolveClassImpl(copy, null));
  }

  /**
   * Re-resolving a class that is absent from the tx-local copy fails loudly rather than silently
   * linking a shared object. A class created in the committed schema after the copy was seeded is
   * not in the copy, so re-resolving it must throw.
   */
  @Test
  public void reresolveClassImplThrowsWhenClassAbsentFromCopy() {
    var committed = committedSchema();
    var copy = session.computeInTx(tx -> committed.copyForTx(session));

    // Create a class in the committed schema AFTER the copy was built, so it is absent from the copy.
    session.getMetadata().getSchema().createClass("AbsentFromCopy");
    var freshlyCommittedImpl = committed.getClass("AbsentFromCopy");

    assertThrows(
        "re-resolving a class missing from the copy must fail loudly",
        IllegalStateException.class,
        () -> SchemaProxedResource.reresolveClassImpl(copy, freshlyCommittedImpl));
  }

  /**
   * Property impl re-resolution maps a committed-shared property to the same-named property on the
   * copy's owner class. A property already owned by the copy's graph is returned unchanged, and a
   * null argument stays null.
   */
  @Test
  public void reresolvePropertyImplMapsCommittedPropertyIntoTheCopy() {
    var schema = session.getMetadata().getSchema();
    var cls = (SchemaClassInternal) schema.createClass("WithProp");
    cls.createProperty("field",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING);

    var committed = committedSchema();
    var committedProp = committed.getClass("WithProp").getPropertyInternal("field");
    assertTrue("the committed property must exist before the copy is built", committedProp != null);

    var copy = session.computeInTx(tx -> committed.copyForTx(session));
    var copyProp = copy.getClass("WithProp").getPropertyInternal("field");

    var resolved = SchemaProxedResource.reresolvePropertyImpl(copy, committedProp);
    assertSame("a committed property impl must re-resolve to the copy's same-named property",
        copyProp, resolved);
    assertSame("the re-resolved property's owner class must be bound to the copy",
        copy, resolved.getOwnerClass().getOwner());

    assertSame("a property already owned by the copy must be returned unchanged",
        copyProp, SchemaProxedResource.reresolvePropertyImpl(copy, copyProp));

    assertNull("a null property argument must stay null",
        SchemaProxedResource.reresolvePropertyImpl(copy, null));
  }
}
