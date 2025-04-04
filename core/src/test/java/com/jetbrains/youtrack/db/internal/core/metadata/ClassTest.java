package com.jetbrains.youtrack.db.internal.core.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Assert;
import org.junit.Test;

public class ClassTest extends BaseMemoryInternalDatabase {

  public static final String SHORTNAME_CLASS_NAME = "TestShortName";

  @Test
  public void testRename() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("ClassName");

    final var storage = session.getStorage();
    final var paginatedStorage = (AbstractStorage) storage;
    final var writeCache = paginatedStorage.getWriteCache();
    Assert.assertTrue(writeCache.exists("classname" + PaginatedCollection.DEF_EXTENSION));

    oClass.setName("ClassNameNew");

    assertFalse(writeCache.exists("classname" + PaginatedCollection.DEF_EXTENSION));
    Assert.assertTrue(writeCache.exists("classnamenew" + PaginatedCollection.DEF_EXTENSION));

    oClass.setName("ClassName");

    assertFalse(writeCache.exists("classnamenew" + PaginatedCollection.DEF_EXTENSION));
    Assert.assertTrue(writeCache.exists("classname" + PaginatedCollection.DEF_EXTENSION));
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
