package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.index.engine.RemoteIndexEngine;
import org.junit.Test;

/**
 * Unit tests for {@link DefaultIndexFactory} covering type/algorithm discovery, index instance
 * creation, version lookup, and index engine creation for memory and disk storage types.
 */
public class DefaultIndexFactoryTest extends DbTestBase {

  private final DefaultIndexFactory factory = new DefaultIndexFactory();

  // -----------------------------------------------------------------------
  //  getTypes / getAlgorithms
  // -----------------------------------------------------------------------

  /**
   * getTypes must return exactly UNIQUE and NOTUNIQUE — the two supported index types.
   */
  @Test
  public void getTypes_returnsUniqueAndNotUnique() {
    var types = factory.getTypes();
    assertTrue("UNIQUE must be in the types set",
        types.contains(SchemaClass.INDEX_TYPE.UNIQUE.toString()));
    assertTrue("NOTUNIQUE must be in the types set",
        types.contains(SchemaClass.INDEX_TYPE.NOTUNIQUE.toString()));
    assertEquals("Only two index types should be declared", 2, types.size());
  }

  /**
   * getAlgorithms must return BTREE as the only supported algorithm.
   */
  @Test
  public void getAlgorithms_returnsBtreeOnly() {
    var algos = factory.getAlgorithms();
    assertTrue("BTREE must be in the algorithms set",
        algos.contains(DefaultIndexFactory.BTREE_ALGORITHM));
    assertEquals("Only one algorithm should be declared", 1, algos.size());
  }

  // -----------------------------------------------------------------------
  //  createIndex (storage-only overload)
  // -----------------------------------------------------------------------

  /**
   * createIndex with UNIQUE type and the database storage must return an IndexUnique instance.
   */
  @Test
  public void createIndex_uniqueType_returnsIndexUniqueInstance() {
    var storage = session.getStorage();
    var idx = factory.createIndex(SchemaClass.INDEX_TYPE.UNIQUE.toString(), storage);
    assertNotNull("createIndex must not return null for UNIQUE", idx);
    assertTrue("createIndex(UNIQUE) must return an IndexUnique",
        idx instanceof IndexUnique);
  }

  /**
   * createIndex with NOTUNIQUE type must return an IndexNotUnique instance.
   */
  @Test
  public void createIndex_notUniqueType_returnsIndexNotUniqueInstance() {
    var storage = session.getStorage();
    var idx = factory.createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(), storage);
    assertNotNull("createIndex must not return null for NOTUNIQUE", idx);
    assertTrue("createIndex(NOTUNIQUE) must return an IndexNotUnique",
        idx instanceof IndexNotUnique);
  }

  /**
   * createIndex with an unsupported index type must throw ConfigurationException.
   */
  @Test(expected = ConfigurationException.class)
  public void createIndex_unsupportedType_throwsConfigurationException() {
    factory.createIndex("FULLTEXT_UNSUPPORTED", session.getStorage());
  }

  // -----------------------------------------------------------------------
  //  getLastVersion
  // -----------------------------------------------------------------------

  /**
   * getLastVersion for BTREE must return the same version constant as BTreeIndexEngine.VERSION.
   */
  @Test
  public void getLastVersion_btreeAlgorithm_returnsBtreeVersion() {
    assertEquals("BTREE version must match BTreeIndexEngine.VERSION",
        BTreeIndexEngine.VERSION, factory.getLastVersion(DefaultIndexFactory.BTREE_ALGORITHM));
  }

  /**
   * getLastVersion for an unknown algorithm must throw IllegalStateException.
   */
  @Test(expected = IllegalStateException.class)
  public void getLastVersion_unknownAlgorithm_throwsIllegalStateException() {
    factory.getLastVersion("UNKNOWN_ALGO");
  }

  // -----------------------------------------------------------------------
  //  createIndexEngine (memory/disk storage)
  // -----------------------------------------------------------------------

  /** Build a minimal IndexEngineData for the given multivalue flag using the full constructor. */
  private static IndexEngineData buildEngineData(int id, String name, boolean multivalue) {
    // Full constructor: indexId, fileBaseId, name, algorithm, indexType, durableInNonTxMode,
    // version, apiVersion, multivalue, valueSerializerId, keySerializedId, isAutomatic,
    // keyTypes, nullValuesSupport, keySize, encryption, encryptionOptions, engineProperties
    return new IndexEngineData(id, id, name, DefaultIndexFactory.BTREE_ALGORITHM,
        multivalue
            ? SchemaClass.INDEX_TYPE.NOTUNIQUE.toString()
            : SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        true, // durableInNonTxMode (deprecated, unused by engine ctor)
        -1, // version: -1 → factory calls getLastVersion(BTREE)
        1, // apiVersion
        multivalue,
        (byte) 0, (byte) 0,
        true, // isAutomatic
        null, // keyTypes
        true, // nullValuesSupport
        1, // keySize
        null, // encryption
        null, // encryptionOptions
        null); // engineProperties
  }

  /**
   * createIndexEngine for a single-value (non-multivalue) BTREE index on a memory/disk storage
   * must return a BTreeSingleValueIndexEngine instance — pin the type so a future refactor
   * that returns a different (or null) engine for the SV path is caught here.
   */
  @Test
  public void createIndexEngine_singleValueBtree_returnsSingleValueEngine() {
    var storage = session.getStorage();
    var engine = factory.createIndexEngine(storage, buildEngineData(0, "sv_engine_test", false));
    assertNotNull("createIndexEngine must not return null for single-value BTREE", engine);
    assertTrue("Single-value BTREE must return BTreeSingleValueIndexEngine",
        engine instanceof BTreeSingleValueIndexEngine);
  }

  /**
   * createIndexEngine for a multivalue BTREE index on a memory/disk storage must return a
   * BTreeMultiValueIndexEngine instance — pin the type for the same reason as the SV variant.
   */
  @Test
  public void createIndexEngine_multivalueBtree_returnsMultiValueEngine() {
    var storage = session.getStorage();
    var engine = factory.createIndexEngine(storage, buildEngineData(1, "mv_engine_test", true));
    assertNotNull("createIndexEngine must not return null for multi-value BTREE", engine);
    assertTrue("Multi-value BTREE must return BTreeMultiValueIndexEngine",
        engine instanceof BTreeMultiValueIndexEngine);
  }

  /**
   * createIndexEngine with a null algorithm must throw IndexException before any storage
   * dispatch is attempted.
   */
  @Test(expected = IndexException.class)
  public void createIndexEngine_nullAlgorithm_throwsIndexException() {
    var storage = session.getStorage();
    var data = new IndexEngineData(2, 2, "null_algo_test", null,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        true, -1, 1, false, (byte) 0, (byte) 0, true, null, true, 1, null, null, null);
    factory.createIndexEngine(storage, data);
  }

  /**
   * createIndexEngine for a storage whose {@code getType()} returns {@code "remote"} must
   * return a {@link RemoteIndexEngine} regardless of the algorithm or multivalue flag.
   * The remote storage type is used by non-embedded (client/server) connections, where index
   * operations are forwarded to the server rather than executed locally.
   *
   * <p>The id and name from the {@code IndexEngineData} must be propagated to the returned
   * engine — a regression that constructs the engine with hard-coded or swapped values
   * (e.g., from the storage name instead of the data name) would not be caught by an
   * instanceof-only check.
   */
  @Test
  public void createIndexEngine_remoteStorage_returnsRemoteIndexEngineWithDataIdAndName() {
    var remoteStorage = mock(Storage.class);
    when(remoteStorage.getType()).thenReturn("remote");
    when(remoteStorage.getName()).thenReturn("remote-db");

    var data = buildEngineData(99, "remote_engine_test", false);
    var engine = factory.createIndexEngine(remoteStorage, data);

    assertNotNull("createIndexEngine must not return null for remote storage", engine);
    assertTrue("Remote storage type must produce a RemoteIndexEngine",
        engine instanceof RemoteIndexEngine);
    var remote = (RemoteIndexEngine) engine;
    assertEquals("RemoteIndexEngine must carry the id from IndexEngineData",
        99, remote.getId());
    assertEquals("RemoteIndexEngine must carry the name from IndexEngineData",
        "remote_engine_test", remote.getName());
  }
}
