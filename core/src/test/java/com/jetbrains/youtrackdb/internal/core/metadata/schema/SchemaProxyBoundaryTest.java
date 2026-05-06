package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/**
 * Boundary-case pin for {@link SchemaProxy}, the session-bound thin wrapper that
 * {@code session.getMetadata().getSchema()} returns. This complements
 * {@link SchemaClassProxyBoundaryTest} (which covers {@link SchemaClassProxy}) — the schema-
 * level proxy has its own dispatch boundary: every method asserts the session is active and
 * re-wraps any returned {@link SchemaClass} in a fresh {@link SchemaClassProxy}.
 *
 * <p>This test pins:
 * <ul>
 *   <li>every {@code createClass} / {@code getOrCreateClass} / {@code createAbstractClass}
 *       overload returns a {@code SchemaClassProxy} bound to the same session — never the raw
 *       impl, never the snapshot;</li>
 *   <li>{@code getClass(String)}, {@code getClass(Class&lt;?&gt;)},
 *       {@code getClassByCollectionId}, and {@code getClassInternal} return proxies for
 *       existing classes and {@code null} for missing ones;</li>
 *   <li>{@code getClasses()} and {@code getClassesRelyOnCollection} return collections whose
 *       members are all proxies;</li>
 *   <li>{@code getClass(null)} and {@code getClass((Class&lt;?&gt;) null)} return null
 *       cleanly;</li>
 *   <li>{@code existsClass(null)} returns false;</li>
 *   <li>{@code makeSnapshot()} returns the cached {@link ImmutableSchema} from the underlying
 *       {@link SchemaShared};</li>
 *   <li>every proxy method that asserts the session is active throws
 *       {@link SessionNotActivatedException} when invoked from a thread the session is not
 *       active on (only relevant when Java assertions are enabled — surefire enables them via
 *       {@code -ea} on the {@code <argLine>});</li>
 *   <li>{@code reload()} returns {@code this} (fluent API) and rebuilds the schema from
 *       storage;</li>
 *   <li>{@code dropClass} is idempotent (dropping a non-existent class is a no-op via the
 *       embedded path — callers can rely on this);</li>
 *   <li>{@code getIndexes()} and {@code indexExists} delegate through the proxy to the index
 *       manager;</li>
 *   <li>{@code addBlobCollection} and {@code removeBlobCollection} round-trip via the proxy.
 *   </li>
 * </ul>
 */
public class SchemaProxyBoundaryTest extends DbTestBase {

  private final List<Thread> spawnedWorkers = new CopyOnWriteArrayList<>();

  /**
   * Tracked worker spawn helper — registers each worker for bounded join in the {@code @After}
   * hook. Keeps the surefire JVM safe against a leaked daemon-less worker if a test's
   * synchronization path misbehaves (e.g., the inactive-session assert is removed and the
   * worker silently completes the lookup instead of throwing).
   */
  private Thread spawn(Runnable body, String name) {
    var t = new Thread(body, name);
    spawnedWorkers.add(t);
    t.start();
    return t;
  }

  @After
  public void joinSpawnedWorkers() throws InterruptedException {
    for (var t : spawnedWorkers) {
      t.join(5_000);
    }
    spawnedWorkers.clear();
  }

  @Test
  public void getMetadataGetSchemaReturnsASchemaProxy() {
    // Pin: the session.getMetadata().getSchema() return type is always a SchemaProxy under
    // embedded storage. This is the entry point for every other proxy operation.
    var schema = session.getMetadata().getSchema();
    assertTrue("getMetadata().getSchema() must return a SchemaProxy: " + schema.getClass(),
        schema instanceof SchemaProxy);
  }

  @Test
  public void allCreateClassOverloadsReturnProxies() {
    // Pin every createClass / createAbstractClass / getOrCreateClass overload returns a
    // SchemaClassProxy. Naive implementations might return the raw impl when handed an empty
    // superclasses array.
    Schema schema = session.getMetadata().getSchema();
    var schemaInternal = (SchemaInternal) schema;

    var c1 = schema.createClass("ProxyCreate1");
    assertTrue(c1 instanceof SchemaClassProxy);

    var sup = schema.createClass("ProxyCreateSup");
    var c2 = schema.createClass("ProxyCreate2", sup);
    assertTrue(c2 instanceof SchemaClassProxy);
    assertTrue(c2.isSubClassOf(sup));

    var c3 = schema.createClass("ProxyCreate3", new SchemaClass[] {sup});
    assertTrue(c3 instanceof SchemaClassProxy);

    // The (String, SchemaClass, int[]) and (String, int[], SchemaClass...) and
    // (String, int, SchemaClass...) overloads are exposed on SchemaInternal — the proxy
    // implements that interface directly.
    var c4 = schemaInternal.createClass("ProxyCreate4", sup, new int[] {-1});
    assertTrue(c4 instanceof SchemaClassProxy);
    assertTrue("collection ids = {-1} on a class must mark it abstract",
        c4.isAbstract());

    var c5 = schemaInternal.createClass("ProxyCreate5", new int[] {-1}, sup);
    assertTrue(c5 instanceof SchemaClassProxy);

    var c6 = schemaInternal.createClass("ProxyCreate6", 2, sup);
    assertTrue(c6 instanceof SchemaClassProxy);
    assertEquals("createClass with collections=2 must allocate two collection ids",
        2, c6.getCollectionIds().length);

    var c7 = schema.createAbstractClass("ProxyAbs1");
    assertTrue(c7 instanceof SchemaClassProxy);
    assertTrue(c7.isAbstract());

    var c8 = schema.createAbstractClass("ProxyAbs2", sup);
    assertTrue(c8 instanceof SchemaClassProxy);

    var c9 = schema.createAbstractClass("ProxyAbs3", new SchemaClass[] {sup});
    assertTrue(c9 instanceof SchemaClassProxy);
  }

  @Test
  public void getOrCreateClassReturnsProxyAndIsIdempotent() {
    // SchemaProxy.getOrCreateClass must wrap both arms (existing class and freshly created
    // class) in a SchemaClassProxy.
    Schema schema = session.getMetadata().getSchema();
    var first = schema.getOrCreateClass("GetOrCreateProxy");
    assertTrue(first instanceof SchemaClassProxy);

    // Second call must hit the "already exists" arm.
    var second = schema.getOrCreateClass("GetOrCreateProxy");
    assertTrue(second instanceof SchemaClassProxy);
    assertEquals(first.getName(), second.getName());

    // Null name → null return on the (String, SchemaClass) overload.
    assertNull("getOrCreateClass(null, null) must return null cleanly",
        schema.getOrCreateClass(null, (SchemaClass) null));

    // Varargs supers form — also returns a proxy.
    var sup = schema.createClass("GetOrCreateSup");
    var third = schema.getOrCreateClass("GetOrCreateProxyChild", sup);
    assertTrue(third instanceof SchemaClassProxy);
  }

  @Test
  public void getClassReturnsProxyForExistingAndNullForMissing() {
    // SchemaProxy.getClass(String) / .getClass(Class<?>) / .getClassByCollectionId all wrap
    // results in proxies and return null cleanly for missing entries.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("LookupProxy");
    int collId = cls.getCollectionIds()[0];

    assertTrue(schema.getClass("LookupProxy") instanceof SchemaClassProxy);
    assertNull(schema.getClass("DoesNotExist"));
    assertNull(schema.getClass((String) null));
    assertNull(schema.getClass((Class<?>) null));

    var byColl = schema.getClassByCollectionId(collId);
    assertNotNull(byColl);
    assertTrue(byColl instanceof SchemaClassProxy);
    assertEquals("LookupProxy", byColl.getName());

    assertNull("unknown collection id must return null",
        schema.getClassByCollectionId(-99999));
  }

  @Test
  public void getClassesAndGetClassesRelyOnCollectionContainOnlyProxies() {
    // SchemaProxy.getClasses iterates the underlying classes and re-wraps each. Pin that every
    // entry is a SchemaClassProxy bound to this session.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("ProxyEnum1");
    schema.createClass("ProxyEnum2");

    var classes = schema.getClasses();
    assertFalse(classes.isEmpty());
    for (var c : classes) {
      assertTrue("getClasses entries must be SchemaClassProxy: " + c.getClass(),
          c instanceof SchemaClassProxy);
      assertSame(session, ((SchemaClassProxy) c).getBoundToSession());
    }

    // getClassesRelyOnCollection — same wrapping invariant.
    var cls = (SchemaClassInternal) schema.getClass("ProxyEnum1");
    int collId = cls.getCollectionIds()[0];
    var collName = session.getCollectionNameById(collId);
    assertNotNull(collName);
    var rely = ((SchemaInternal) schema).getClassesRelyOnCollection(collName, session);
    for (var c : rely) {
      assertTrue("getClassesRelyOnCollection entries must be SchemaClassProxy: " + c.getClass(),
          c instanceof SchemaClassProxy);
    }
  }

  @Test
  public void getClassInternalReturnsProxyForLiveLookup() {
    // SchemaProxy.getClassInternal returns SchemaClassInternal — for the live (non-snapshot)
    // path it's a SchemaClassProxy. The snapshot's getClassInternal returns SchemaImmutableClass
    // (covered by the snapshot test); this pin is for the live path through the proxy.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("InternalLookup");
    var fromInternal = ((SchemaInternal) schema).getClassInternal("InternalLookup");
    assertTrue("getClassInternal on the live SchemaProxy must return a SchemaClassProxy: "
        + fromInternal.getClass(), fromInternal instanceof SchemaClassProxy);
    assertNull(((SchemaInternal) schema).getClassInternal("DoesNotExist"));
  }

  @Test
  public void existsClassNullReturnsFalse() {
    // SchemaProxy.existsClass(null) must return false without throwing — defends the null guard
    // before delegating to SchemaShared.existsClass.
    Schema schema = session.getMetadata().getSchema();
    assertFalse(schema.existsClass(null));
    assertFalse(schema.existsClass("DoesNotExist"));
  }

  @Test
  public void makeSnapshotRoundTripsThroughProxy() {
    // SchemaProxy.makeSnapshot delegates to SchemaShared.makeSnapshot. Pin: a snapshot is
    // returned; the snapshot reflects the live class set at capture time.
    var schemaInternal = (SchemaInternal) session.getMetadata().getSchema();
    schemaInternal.createClass("SnapshotProxy");

    var snapshot = schemaInternal.makeSnapshot();
    assertNotNull(snapshot);
    assertTrue("snapshot must contain the freshly created class",
        snapshot.existsClass("SnapshotProxy"));
  }

  @Test
  public void reloadReturnsThisForFluentChain() {
    // SchemaProxy.reload returns 'this' (the proxy) so callers can chain. Pin the fluent
    // contract.
    var schemaProxy = (SchemaProxy) session.getMetadata().getSchema();
    var afterReload = schemaProxy.reload();
    assertSame("reload must return the same proxy", schemaProxy, afterReload);
  }

  @Test
  public void getVersionAndGetIdentityRouteThroughDelegateWithoutSessionAssertion() {
    // SchemaProxy.getVersion and getIdentity do NOT assert the session is active (they are read-
    // only field accessors on the SchemaShared). Pin: both return values agree with the
    // SchemaShared version/identity.
    var proxy = (SchemaProxy) session.getMetadata().getSchema();
    var schemaShared = session.getSharedContext().getSchema();
    assertEquals("proxy.getVersion must match SchemaShared.getVersion",
        schemaShared.getVersion(), proxy.getVersion());
    assertSame("proxy.getIdentity must be the same RID instance",
        schemaShared.getIdentity(), proxy.getIdentity());
  }

  @Test
  public void getIndexesAndIndexExistsRouteThroughIndexManager() {
    // SchemaProxy.getIndexes and indexExists delegate to session.getSharedContext()
    // .getIndexManager(). Pin: a freshly created index appears in both.
    Schema schema = session.getMetadata().getSchema();
    var cls = schema.createClass("IdxProxy");
    cls.createProperty("f", PropertyType.STRING);
    cls.createIndex("IdxProxy.fIdx", SchemaClass.INDEX_TYPE.NOTUNIQUE, "f");

    assertTrue("indexExists on the proxy must report the freshly created index",
        schema.indexExists("IdxProxy.fIdx"));
    assertTrue("getIndexes on the proxy must include the freshly created index",
        schema.getIndexes().contains("IdxProxy.fIdx"));

    var def = schema.getIndexDefinition("IdxProxy.fIdx");
    assertNotNull(def);
    assertEquals("IdxProxy", def.className());

    // Unknown index → IllegalArgumentException.
    try {
      schema.getIndexDefinition("DoesNotExist");
      fail("getIndexDefinition must throw for unknown index");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("DoesNotExist"));
    }
  }

  @Test
  public void blobCollectionRoundTripThroughProxy() {
    // SchemaProxy.addBlobCollection and removeBlobCollection delegate to the SchemaShared
    // mutators. Pin: a new collection id added as a blob collection appears in
    // getBlobCollections, and removal drops it.
    var proxy = (SchemaProxy) session.getMetadata().getSchema();
    int newCollId = session.addCollection("blobProxyColl");
    proxy.addBlobCollection(newCollId);
    assertTrue("blob collection must contain the newly added id",
        proxy.getBlobCollections().contains(newCollId));

    proxy.removeBlobCollection("blobProxyColl");
    assertFalse("blob collection must NOT contain the removed id",
        proxy.getBlobCollections().contains(newCollId));
  }

  @Test
  public void countClassesIncreasesWithCreate() {
    Schema schema = session.getMetadata().getSchema();
    int countBefore = schema.countClasses();
    schema.createClass("CountClsProxy");
    int countAfter = schema.countClasses();
    assertEquals("countClasses must report the post-create count",
        countBefore + 1, countAfter);
  }

  @Test
  public void getCollectionSelectionFactoryRoutesThroughProxy() {
    // SchemaProxy.getCollectionSelectionFactory delegates to SchemaShared. Pin: the factory is
    // non-null on the proxy and is the same instance the SchemaShared returns.
    var proxy = (SchemaInternal) session.getMetadata().getSchema();
    assertNotNull(proxy.getCollectionSelectionFactory());
    assertSame(session.getSharedContext().getSchema().getCollectionSelectionFactory(),
        proxy.getCollectionSelectionFactory());
  }

  @Test
  public void createGlobalPropertyAndGetGlobalPropertyByIdAndGetGlobalProperties() {
    // SchemaProxy.createGlobalProperty / getGlobalPropertyById / getGlobalProperties delegate
    // to the SchemaShared global-property registry. Pin the round-trip.
    Schema schema = session.getMetadata().getSchema();
    schema.createGlobalProperty("proxyGlobal", PropertyType.SHORT, 900);
    var prop = schema.getGlobalPropertyById(900);
    assertNotNull(prop);
    assertEquals("proxyGlobal", prop.getName());
    assertEquals(PropertyType.SHORT, prop.getType());

    // The properties list is sparse — uninitialized id slots are null. Filter nulls before
    // matching the new entry by name.
    var all = schema.getGlobalProperties();
    assertTrue("getGlobalProperties must contain the freshly created property",
        all.stream().anyMatch(p -> p != null && "proxyGlobal".equals(p.getName())));
  }

  @Test
  public void dropClassRemovesEntryFromProxyView() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("DropProxy");
    assertTrue(schema.existsClass("DropProxy"));
    schema.dropClass("DropProxy");
    assertFalse("dropClass must remove the entry from the live schema view",
        schema.existsClass("DropProxy"));
  }

  @Test
  public void inactiveSessionTriggersSessionNotActivatedException() throws InterruptedException {
    // SchemaProxy methods all begin with `assert session.assertIfNotActive()`, which throws
    // SessionNotActivatedException when the calling thread does not have the session active.
    // Surefire runs with -ea (assertions enabled), so this assert is live.
    Schema schema = session.getMetadata().getSchema();
    var failure = new AtomicReference<Throwable>();
    var caught = new AtomicReference<Class<? extends Throwable>>();

    // Spawn a worker that has NOT activated the session — calling existsClass on the proxy
    // must throw SessionNotActivatedException via the assert. The tracked spawn() + @After
    // join discipline guards against a worker that silently completes the lookup (the very
    // regression this test is designed to surface) leaking past the test boundary.
    var t = spawn(() -> {
      try {
        // Use existsClass (not createClass) — existsClass is a read-only path; if the assert
        // is removed by a future regression, the underlying SchemaShared.existsClass would
        // succeed silently. Pinning the assert here guards the regression.
        schema.existsClass("AnyName");
        // If we got here, the session activation check did not fire — record this so the
        // assertion below fails informatively.
      } catch (Throwable th) {
        caught.set(th.getClass());
        failure.set(th);
      }
    }, "SchemaProxyBoundaryTest-inactive-thread");
    t.join(5_000);

    assertNotNull("inactive-session call must have thrown — got null failure",
        failure.get());
    // Walk the cause chain — the assert expansion sometimes wraps the underlying throwable.
    Throwable cur = failure.get();
    boolean foundExpected = false;
    while (cur != null) {
      if (cur instanceof SessionNotActivatedException) {
        foundExpected = true;
        break;
      }
      cur = cur.getCause();
    }
    assertTrue("expected SessionNotActivatedException in cause chain, got: " + caught.get()
        + " message=" + (failure.get() == null ? "<none>" : failure.get().getMessage()),
        foundExpected);
  }

  @Test
  public void getClassByJavaClassRoutesThroughGetName() {
    // SchemaProxy.getClass(Class<?>) routes through delegate.getClass(iClass.getName()).
    // The ImmutableSchema variant uses getSimpleName — the live proxy uses getName (the FQCN).
    // Create a class with the FQCN of a real class so the live proxy can resolve it.
    Schema schema = session.getMetadata().getSchema();
    schema.createClass(LocalShape.class.getName());
    var resolved = schema.getClass(LocalShape.class);
    assertNotNull("schema.getClass(LocalShape.class) must resolve via the FQCN on the live proxy",
        resolved);
    assertEquals(LocalShape.class.getName(), resolved.getName());
  }

  /** Marker class for the live-proxy {@code getClass(Class<?>)} resolution test. */
  private static final class LocalShape {
  }
}
