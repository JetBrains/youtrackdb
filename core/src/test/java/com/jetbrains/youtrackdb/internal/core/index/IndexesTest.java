package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Test;

/**
 * Unit tests for {@link Indexes} — the static dispatcher that delegates index creation to
 * registered {@link IndexFactory} implementations. Tests verify factory discovery, algorithm
 * selection defaults, and index instance creation via the dispatcher.
 */
public class IndexesTest extends DbTestBase {

  // -----------------------------------------------------------------------
  //  getAllFactories
  // -----------------------------------------------------------------------

  /**
   * getAllFactories must return at least one factory (DefaultIndexFactory, registered via
   * META-INF/services).
   */
  @Test
  public void getAllFactories_returnsAtLeastOneFactory() {
    var it = Indexes.getAllFactories();
    assertTrue("At least one IndexFactory must be discoverable via ServiceLoader", it.hasNext());
  }

  // -----------------------------------------------------------------------
  //  getFactory
  // -----------------------------------------------------------------------

  /**
   * getFactory for UNIQUE + BTREE must return the DefaultIndexFactory (the only registered
   * factory that declares both).
   */
  @Test
  public void getFactory_uniqueAndBtree_returnsDefaultIndexFactory() {
    var factory = Indexes.getFactory(
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), DefaultIndexFactory.BTREE_ALGORITHM);
    assertNotNull("getFactory must return a non-null factory for UNIQUE/BTREE", factory);
    assertTrue("Factory for UNIQUE/BTREE must be DefaultIndexFactory",
        factory instanceof DefaultIndexFactory);
  }

  /**
   * getFactory for NOTUNIQUE + BTREE must also return the DefaultIndexFactory.
   */
  @Test
  public void getFactory_notUniqueAndBtree_returnsDefaultIndexFactory() {
    var factory = Indexes.getFactory(
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(), DefaultIndexFactory.BTREE_ALGORITHM);
    assertNotNull("getFactory must return a non-null factory for NOTUNIQUE/BTREE", factory);
    assertTrue("Factory for NOTUNIQUE/BTREE must be DefaultIndexFactory",
        factory instanceof DefaultIndexFactory);
  }

  /**
   * getFactory for an unknown index type must throw IndexException.
   */
  @Test(expected = IndexException.class)
  public void getFactory_unknownTypeAndAlgorithm_throwsIndexException() {
    Indexes.getFactory("UNKNOWN_TYPE", "UNKNOWN_ALGO");
  }

  // -----------------------------------------------------------------------
  //  chooseDefaultIndexAlgorithm
  // -----------------------------------------------------------------------

  /**
   * chooseDefaultIndexAlgorithm for UNIQUE must return BTREE.
   */
  @Test
  public void chooseDefaultIndexAlgorithm_unique_returnsBtree() {
    assertEquals("UNIQUE must map to BTREE by default",
        DefaultIndexFactory.BTREE_ALGORITHM,
        Indexes.chooseDefaultIndexAlgorithm(SchemaClass.INDEX_TYPE.UNIQUE.name()));
  }

  /**
   * chooseDefaultIndexAlgorithm for NOTUNIQUE must return BTREE.
   */
  @Test
  public void chooseDefaultIndexAlgorithm_notUnique_returnsBtree() {
    assertEquals("NOTUNIQUE must map to BTREE by default",
        DefaultIndexFactory.BTREE_ALGORITHM,
        Indexes.chooseDefaultIndexAlgorithm(SchemaClass.INDEX_TYPE.NOTUNIQUE.name()));
  }

  /**
   * chooseDefaultIndexAlgorithm for an unknown/unsupported type must return null (no default
   * algorithm is registered for it).
   */
  @Test
  public void chooseDefaultIndexAlgorithm_unknownType_returnsNull() {
    assertNull("An unrecognised type must yield null from chooseDefaultIndexAlgorithm",
        Indexes.chooseDefaultIndexAlgorithm("SOME_FUTURE_TYPE"));
  }

  // -----------------------------------------------------------------------
  //  createIndexInstance (storage-only overload)
  // -----------------------------------------------------------------------

  /**
   * createIndexInstance for UNIQUE + BTREE must return an IndexUnique instance backed by the
   * real in-memory storage.
   */
  @Test
  public void createIndexInstance_uniqueBtree_returnsIndexUnique() {
    var storage = session.getStorage();
    var idx = Indexes.createIndexInstance(
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), DefaultIndexFactory.BTREE_ALGORITHM, storage);
    assertNotNull("createIndexInstance must not return null for UNIQUE/BTREE", idx);
    assertTrue("createIndexInstance(UNIQUE/BTREE) must return IndexUnique",
        idx instanceof IndexUnique);
  }

  /**
   * createIndexInstance for NOTUNIQUE + BTREE must return an IndexNotUnique instance.
   */
  @Test
  public void createIndexInstance_notUniqueBtree_returnsIndexNotUnique() {
    var storage = session.getStorage();
    var idx = Indexes.createIndexInstance(
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(), DefaultIndexFactory.BTREE_ALGORITHM, storage);
    assertNotNull("createIndexInstance must not return null for NOTUNIQUE/BTREE", idx);
    assertTrue("createIndexInstance(NOTUNIQUE/BTREE) must return IndexNotUnique",
        idx instanceof IndexNotUnique);
  }
}
