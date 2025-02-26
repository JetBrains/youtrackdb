package com.jetbrains.youtrack.db.internal.core.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.PaginatedCluster;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import java.util.Locale;
import org.junit.Assert;
import org.junit.Test;

public class ClassTest extends BaseMemoryInternalDatabase {

  public static final String SHORTNAME_CLASS_NAME = "TestShortName";

  @Test
  public void testShortName() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass(SHORTNAME_CLASS_NAME);
    Assert.assertNull(oClass.getShortName(session));
    Assert.assertNull(queryShortName());

    final var storage = session.getStorage();

    if (storage instanceof AbstractPaginatedStorage paginatedStorage) {
      final var writeCache = paginatedStorage.getWriteCache();
      Assert.assertTrue(
          writeCache.exists(
              SHORTNAME_CLASS_NAME.toLowerCase(Locale.ENGLISH) + PaginatedCluster.DEF_EXTENSION));
    }

    var shortName = "shortname";
    oClass.setShortName(session, shortName);
    assertEquals(shortName, oClass.getShortName(session));
    assertEquals(shortName, queryShortName());

    // FAILS, saves null value and stores "null" string (not null value) internally
    shortName = "null";
    oClass.setShortName(session, shortName);
    assertEquals(shortName, oClass.getShortName(session));
    assertEquals(shortName, queryShortName());

    oClass.setShortName(session, null);
    Assert.assertNull(oClass.getShortName(session));
    Assert.assertNull(queryShortName());

    oClass.setShortName(session, "");
    Assert.assertNull(oClass.getShortName(session));
    Assert.assertNull(queryShortName());
  }

  @Test
  public void testShortNameSnapshot() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass(SHORTNAME_CLASS_NAME);
    Assert.assertNull(oClass.getShortName(session));

    var shortName = "shortName";
    oClass.setShortName(session, shortName);
    assertEquals(shortName, oClass.getShortName(session));
    var shorted = schema.getClass(shortName);
    Assert.assertNotNull(shorted);
    assertEquals(shortName, shorted.getShortName(session));
    var intern = session.getMetadata();
    var immSchema = intern.getImmutableSchemaSnapshot();
    shorted = immSchema.getClass(shortName);
    Assert.assertNotNull(shorted);
    assertEquals(shortName, shorted.getShortName(session));
  }

  @Test
  public void testRename() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("ClassName");

    final var storage = session.getStorage();
    final var paginatedStorage = (AbstractPaginatedStorage) storage;
    final var writeCache = paginatedStorage.getWriteCache();
    Assert.assertTrue(writeCache.exists("classname" + PaginatedCluster.DEF_EXTENSION));

    oClass.setName(session, "ClassNameNew");

    assertFalse(writeCache.exists("classname" + PaginatedCluster.DEF_EXTENSION));
    Assert.assertTrue(writeCache.exists("classnamenew" + PaginatedCluster.DEF_EXTENSION));

    oClass.setName(session, "ClassName");

    assertFalse(writeCache.exists("classnamenew" + PaginatedCluster.DEF_EXTENSION));
    Assert.assertTrue(writeCache.exists("classname" + PaginatedCluster.DEF_EXTENSION));
  }

  @Test
  public void testOClassAndOPropertyDescription() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("DescriptionTest");
    var property = oClass.createProperty(session, "property", PropertyType.STRING);
    oClass.setDescription(session, "DescriptionTest-class-description");
    property.setDescription(session, "DescriptionTest-property-description");
    assertEquals("DescriptionTest-class-description", oClass.getDescription(session));
    assertEquals("DescriptionTest-property-description", property.getDescription(session));
    oClass = oSchema.getClass("DescriptionTest");
    property = oClass.getProperty(session, "property");
    assertEquals("DescriptionTest-class-description", oClass.getDescription(session));
    assertEquals("DescriptionTest-property-description", property.getDescription(session));

    oClass = session.getMetadata().getImmutableSchemaSnapshot().getClass("DescriptionTest");
    property = oClass.getProperty(session, "property");
    assertEquals("DescriptionTest-class-description", oClass.getDescription(session));
    assertEquals("DescriptionTest-property-description", property.getDescription(session));
  }

  private String queryShortName() {
    return session.computeInTx(() -> {
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
