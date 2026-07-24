package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.concurrent.atomic.AtomicReference;
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

    var ex =
        assertThrows(
            "re-resolving a class missing from the copy must fail loudly",
            IllegalStateException.class,
            () -> SchemaProxedResource.reresolveClassImpl(copy, freshlyCommittedImpl));
    // The message is load-bearing: it names the absent class and the private-copy refusal, so the
    // failure cannot be mistaken for some incidental IllegalStateException raised elsewhere on the
    // schema path. Assert both so a refactor that throws a generic ISE before reaching the intended
    // check fails this test.
    assertNotNull("the loud reject must carry an explanatory message", ex.getMessage());
    assertTrue(
        "the reject must name the absent class: " + ex.getMessage(),
        ex.getMessage().contains("AbsentFromCopy"));
    assertTrue(
        "the reject must name the transaction-local-schema-view refusal: " + ex.getMessage(),
        ex.getMessage().contains("transaction-local schema view"));
  }

  /**
   * Read-path argument re-resolution is tolerant of an absent class. The two argument-taking read
   * predicates (isSubClassOf / isSuperClassOf) re-resolve their argument through the read-tolerant
   * helper, which must return null rather than throw when the named class is missing from the
   * tx-local copy. A class created in the committed schema after the copy was seeded is absent from
   * the copy; re-resolving it through the read helper must yield null (so the downstream predicate
   * answers false) instead of the loud IllegalStateException the write-path helper raises.
   */
  @Test
  public void reresolveClassImplForReadReturnsNullWhenClassAbsentFromCopy() {
    var committed = committedSchema();
    var copy = session.computeInTx(tx -> committed.copyForTx(session));

    // Create a class in the committed schema AFTER the copy was built, so it is absent from the copy.
    session.getMetadata().getSchema().createClass("AbsentFromCopyForRead");
    var freshlyCommittedImpl = committed.getClass("AbsentFromCopyForRead");

    assertNull(
        "the read-tolerant helper must return null for a class missing from the copy, not throw",
        SchemaProxedResource.reresolveClassImplForRead(copy, freshlyCommittedImpl));

    // A null argument still stays null, and a class already owned by the copy is returned unchanged.
    assertNull("a null argument must stay null on the read path",
        SchemaProxedResource.reresolveClassImplForRead(copy, null));
  }

  /**
   * The proxy read overloads stay total when the argument class is unknown to the resolved schema.
   * On the tier-2 read path (no write-view) the receiver resolves to the committed instance and the
   * argument re-resolves against it; an argument whose class name is absent from the committed
   * schema (here a class living only in an independent tx-local copy) must make isSubClassOf /
   * isSuperClassOf return false rather than throw IllegalStateException. This pins the corrected
   * read contract: a read of a foreign/unknown argument answers false, while the loud write-path
   * resolver is unchanged.
   */
  @Test
  public void readSubclassChecksReturnFalseForArgumentUnknownToResolvedSchema() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("ReadBase");
    // ReadForeign exists at the top level so it is present in both the committed schema and any
    // copy built from it; the copy's impl is captured before the class is dropped from committed.
    schema.createClass("ReadForeign");

    var committed = committedSchema();
    var sideCopy = session.computeInTx(tx -> committed.copyForTx(session));
    // The argument impl is owned by the side copy (owner != committed), so the tier-2 re-resolution
    // looks it up by name in the committed schema rather than returning it unchanged.
    var foreignImpl = sideCopy.getClass("ReadForeign");

    // Drop ReadForeign from the committed schema at the top level (no transaction => legacy path),
    // so the argument's name is now absent from the committed schema the tier-2 read resolves
    // against, while the captured copy impl still holds the (now-foreign) name.
    schema.dropClass("ReadForeign");
    assertNull("the argument class must be absent from the committed schema after the drop",
        committed.getClass("ReadForeign"));
    var foreignArg = new SchemaClassProxy(foreignImpl, session);

    var base = (SchemaClassInternal) schema.getClass("ReadBase");

    // Tier 2: no write-view is seeded, so the base resolves to the committed instance and the
    // argument re-resolves against it. The argument is unknown there, so the read must answer false
    // (historical total-read contract) rather than throw.
    assertNull("the tier-2 read must run with no write-view seeded", session.getTxSchemaState());
    assertFalse(
        "isSubClassOf with an argument unknown to the resolved schema must return false",
        base.isSubClassOf(foreignArg));
    assertFalse(
        "isSuperClassOf with an argument unknown to the resolved schema must return false",
        base.isSuperClassOf(foreignArg));
  }

  /**
   * Linking a superclass created in the same transaction binds the inheritance edge entirely within
   * the tx-local copy: the re-resolution takes the "argument is already a tx-local object"
   * short-circuit (the superclass impl is owned by the copy, not the committed schema), so the
   * child's superclass edge points at the copy's parent object rather than a committed or shared one.
   * This is the hardest input for the no-shared-impl-in-the-private-graph isolation invariant — an
   * argument that is already tx-local — and the {@code addSuperClass} half of the polymorphic
   * membership path, distinct from the create-subclass-of-a-committed-indexed-super case the
   * membership-ripple test covers (which takes the by-name lookup branch instead).
   */
  @Test
  public void addSuperClassToSameTxCreatedParentLinksWithinTheCopy() {
    session.executeInTx(
        tx -> {
          var schema = session.getMetadata().getSchema();
          // Both parent and child are created inside this transaction, so both impls are owned by
          // the tx-local copy. Linking them exercises the already-tx-local short-circuit in
          // reresolveClassImpl rather than the by-name lookup branch.
          var parent = schema.createClass("SameTxParent");
          var child = schema.createClass("SameTxChild");
          child.addSuperClass(parent);

          var copy = session.getTxSchemaState().getTxLocalSchema();
          var resolvedChild =
              (SchemaClassInternal) session.getMetadata().getSchema().getClass("SameTxChild");
          var resolvedParent =
              (SchemaClassInternal) session.getMetadata().getSchema().getClass("SameTxParent");

          var linkedSuper = resolvedChild.getImplementation().getSuperClasses().get(0);
          assertSame(
              "the superclass edge must bind to the copy's parent object, not a shared/committed one",
              resolvedParent.getImplementation(), linkedSuper);
          assertSame(
              "the linked superclass impl must be owned by the tx-local copy",
              copy, linkedSuper.getOwner());
        });
  }

  /**
   * Using a class proxy after the class was dropped earlier in the same transaction fails loudly
   * rather than silently resolving against stale state. The proxy captures its delegate at creation;
   * after the class is dropped from the tx-local copy, the next routed call re-binds the delegate by
   * name through {@code rebindToTxLocal}, which finds the class absent and throws. This is the
   * drop-then-reference loud-failure boundary the commit-time reconciliation track builds on, and it
   * goes through the instance {@code rebindToTxLocal} reached from a real proxy call — a different
   * method and call site from the static {@code reresolveClassImpl} the absent-from-copy test drives.
   */
  @Test
  public void usingAProxyAfterItsClassWasDroppedInTheSameTxFailsLoudly() {
    // Create the class committed at the top level so a proxy for it exists before the transaction.
    session.getMetadata().getSchema().createClass("DropThenUse");

    session.executeInTx(
        tx -> {
          var schema = session.getMetadata().getSchema();
          // Capture the proxy, then drop the class in the same transaction. The drop routes into the
          // tx-local copy (seeding the write-view), so the class is now absent from the copy the
          // captured proxy will re-bind against on its next call.
          var proxy = schema.getClass("DropThenUse");
          schema.dropClass("DropThenUse");

          var ex =
              assertThrows(
                  "a call through a proxy for a class dropped earlier in the tx must fail loudly",
                  IllegalStateException.class,
                  proxy::getName);
          assertNotNull("the loud reject must carry an explanatory message", ex.getMessage());
          assertTrue(
              "the reject must name the dropped class: " + ex.getMessage(),
              ex.getMessage().contains("DropThenUse"));
          assertTrue(
              "the reject must explain the class is absent from the tx-local view: "
                  + ex.getMessage(),
              ex.getMessage().contains("transaction-local schema view"));
        });
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

  /**
   * Seeding the tx-local schema copy for a committed schema whose indexed class already owns a
   * subclass must complete without self-deadlocking or tripping the engage-order guard, and must not
   * record the reconstructed committed classes as changed. The seed's {@code copyForTx} re-parse
   * rebuilds the committed inheritance tree, which ripples the subclass's collection into the
   * indexed superclass and routes through the index-manager's tx-local seam. Before the seeding
   * guard existed, that ripple re-entered {@code ensureTxSchemaState} on the seeding thread (the seed
   * marker is written only after the copy is built), engaging the single-permit mutex a second time
   * and parking forever under disabled assertions, or tripping the engage-order guard with
   * assertions on because the seed holds the committed schema write lock. The committed schema here
   * deliberately has the polymorphic-index shape (an indexed superclass with a subclass) that the
   * other routing tests lack, which is why only this scenario exercises the ripple. The seed runs on
   * a bounded watchdog thread so a regression surfaces as a timeout or a captured throwable rather
   * than hanging the test JVM.
   */
  @Test
  public void seedingWithIndexedSuperclassAndSubclassDoesNotReEnterEngageOrPolluteChangedClasses()
      throws InterruptedException {
    var schema = session.getMetadata().getSchema();
    // Committed schema (built at the top level, outside any transaction): an indexed superclass with
    // a subclass. The subclass's collection is a polymorphic member of the superclass index, so the
    // copyForTx re-parse will ripple it into that index during inheritance rebuild.
    var base = schema.createClass("IndexedBase");
    base.createProperty("key", PropertyType.STRING);
    base.createIndex("IndexedBase.key", SchemaClass.INDEX_TYPE.UNIQUE, "key");
    schema.createClass("IndexedSub", base);

    var seeded = new AtomicReference<TxSchemaState>();
    var changedAtSeed = new AtomicReference<java.util.Set<String>>();
    var failure = new AtomicReference<Throwable>();

    // Run the seed on a separate thread with its own session: a regression that re-engages the
    // single permit parks the seeding thread forever, so the bounded join below converts that hang
    // into a test failure instead of stalling the surefire JVM. A daemon thread cannot keep the JVM
    // alive if it leaks. The worker opens its own session (the committed IndexedBase/IndexedSub
    // schema is visible to it because the top-level creates above are committed to shared storage);
    // a session is thread-confined, so it must be opened and activated on the worker thread rather
    // than reusing the test thread's session.
    var worker =
        new Thread(
            () -> {
              try (var workerSession = openDatabase()) {
                workerSession.activateOnCurrentThread();
                workerSession.executeInTx(
                    tx -> {
                      // First schema write of the transaction: this seeds the tx-local copy, which
                      // re-parses the committed schema and ripples the polymorphic index membership.
                      var state = workerSession.ensureTxSchemaState();
                      seeded.set(state);
                      // Snapshot the changed-class set right after the seed: it must be empty, proving
                      // the committed classes reconstructed during the re-parse were not recorded as
                      // changes of this transaction.
                      changedAtSeed.set(new java.util.HashSet<>(state.getChangedClasses()));
                    });
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "seed-worker");
    worker.setDaemon(true);
    worker.start();
    worker.join(15_000);

    if (worker.isAlive()) {
      worker.interrupt();
      throw new AssertionError(
          "seeding the tx-local copy for an indexed superclass with a subclass did not complete"
              + " within 15s: the seed-time membership ripple re-entered the engage and parked on"
              + " the single-permit mutex");
    }
    if (failure.get() != null) {
      throw new AssertionError(
          "seeding the tx-local copy threw instead of completing (an engage-order trip or a"
              + " re-entrant engage): "
              + failure.get(),
          failure.get());
    }

    assertNotNull("the seed must produce a tx-local schema state", seeded.get());
    assertTrue(
        "the seed must reconstruct the indexed superclass into the tx-local copy",
        seeded.get().getTxLocalSchema().existsClass("IndexedBase"));
    assertEquals(
        "the seed must not record the committed classes reconstructed during the re-parse as"
            + " changed; the changed-class set must be empty right after seeding",
        java.util.Collections.emptySet(),
        changedAtSeed.get());
  }
}
