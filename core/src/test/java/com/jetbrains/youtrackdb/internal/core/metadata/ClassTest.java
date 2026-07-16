package com.jetbrains.youtrackdb.internal.core.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Assert;
import org.junit.Test;

public class ClassTest extends BaseMemoryInternalDatabase {

  public static final String SHORTNAME_CLASS_NAME = "TestShortName";

  /**
   * Verifies that renaming a class is metadata-only at the storage-file level: collection names
   * are counter-only ({@code c_<counter>}, no class-name component), so the class's collection
   * file keeps its exact name across renames — no file is renamed, created, or dropped. The
   * pre-D11 behavior renamed the collection file through the non-WAL-safe
   * {@code writeCache.renameFile} path, which a crash could leave half-renamed; this pins its
   * removal. Data written before the rename must stay reachable through the renamed class.
   */
  @Test
  public void testRename() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("ClassName");

    // Write a record before renaming, so reachability through the renamed class is provable.
    session.executeInTx(tx -> tx.newEntity("ClassName").setProperty("marker", "kept"));

    final var storage = session.getStorage();
    final var paginatedStorage = (AbstractStorage) storage;
    final var writeCache = paginatedStorage.getWriteCache();

    // Get the actual collection name assigned to this class
    var collectionName = session.getCollectionNameById(oClass.getCollectionIds()[0]);
    Assert.assertTrue(writeCache.exists(collectionName + PaginatedCollection.DEF_EXTENSION));

    oClass.setName("ClassNameNew");

    // The rename touched no storage file: the collection keeps its name and its data file.
    assertEquals("a class rename must not rename the class's collection",
        collectionName, session.getCollectionNameById(oClass.getCollectionIds()[0]));
    Assert.assertTrue("the collection file must survive the rename under its original name",
        writeCache.exists(collectionName + PaginatedCollection.DEF_EXTENSION));

    // Rename back: still metadata-only.
    oClass.setName("ClassName");

    assertEquals("a rename back must not rename the class's collection either",
        collectionName, session.getCollectionNameById(oClass.getCollectionIds()[0]));
    Assert.assertTrue(
        writeCache.exists(collectionName + PaginatedCollection.DEF_EXTENSION));

    // The pre-rename record is still reachable through the class.
    session.executeInTx(tx -> {
      try (var rs = session.query("SELECT FROM ClassName")) {
        var rows = rs.stream().toList();
        assertEquals(1, rows.size());
        assertEquals("kept", rows.get(0).getProperty("marker"));
      }
    });
  }

  @Test
  public void testOClassAndOPropertyDescription() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("DescriptionTest");
    var property = oClass.createProperty("property", PropertyType.STRING);
    oClass.setDescription("DescriptionTest-class-description");
    property.setDescription("DescriptionTest-property-description");
    assertEquals("DescriptionTest-class-description", oClass.getDescription());
    assertEquals("DescriptionTest-property-description", property.getDescription());
    oClass = oSchema.getClass("DescriptionTest");
    property = oClass.getProperty("property");
    assertEquals("DescriptionTest-class-description", oClass.getDescription());
    assertEquals("DescriptionTest-property-description", property.getDescription());

    oClass = session.getMetadata().getImmutableSchemaSnapshot().getClass("DescriptionTest");
    property = oClass.getProperty("property");
    assertEquals("DescriptionTest-class-description", oClass.getDescription());
    assertEquals("DescriptionTest-property-description", property.getDescription());
  }

  private String queryShortName() {
    return session.computeInTx(transaction -> {
      var selectShortNameSQL =
          "select shortName from ( select expand(classes) from metadata:schema )"
              + " where name = \""
              + SHORTNAME_CLASS_NAME
              + "\"";
      try (var result = session.query(selectShortNameSQL)) {
        String name = result.next().getProperty("shortName");
        assertFalse(result.hasNext());
        return name;
      }
    });
  }
}
