package com.jetbrains.youtrackdb.internal.core.db.tool;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class TestSchemaImportExport extends DbTestBase {

  @Test
  public void testExportImportCustomData() throws IOException {
    youTrackDB.createIfNotExists(
        TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    var output = new ByteArrayOutputStream();
    try (var db =
        (DatabaseSessionEmbedded)
            youTrackDB.open(TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      var clazz = db.getMetadata().getSchema().createClass("Test");
      clazz.createProperty("some", PropertyType.STRING);
      clazz.setCustom("testcustom", "test");
      var exp = new DatabaseExport(db, output, new MockOutputListener());
      exp.exportDatabase();
    } finally {
      youTrackDB.drop(TestSchemaImportExport.class.getSimpleName());
    }

    youTrackDB.createIfNotExists(
        "imp_" + TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    try (var sessionOne =
        (DatabaseSessionInternal)
            youTrackDB.open("imp_" + TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      var imp =
          new DatabaseImport(
              (DatabaseSessionEmbedded) sessionOne, new ByteArrayInputStream(output.toByteArray()),
              new MockOutputListener());
      imp.importDatabase();
      var clas1 = sessionOne.getMetadata().getSchema().getClass("Test");
      Assert.assertNotNull(clas1);
      Assert.assertEquals("test", clas1.getCustom("testcustom"));
    } finally {
      youTrackDB.drop("imp_" + TestSchemaImportExport.class.getSimpleName());
    }
  }

  @Test
  public void testExportImportDefaultValue() throws IOException {
    youTrackDB.createIfNotExists(
        TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    var output = new ByteArrayOutputStream();

    try (var db =
        (DatabaseSessionEmbedded)
            youTrackDB.open(TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      var clazz = db.getMetadata().getSchema().createClass("Test");
      clazz.createProperty("bla", PropertyType.STRING).setDefaultValue("something");
      var exp = new DatabaseExport(db, output, new MockOutputListener());
      exp.exportDatabase();
    } finally {
      youTrackDB.drop(TestSchemaImportExport.class.getSimpleName());
    }

    youTrackDB.createIfNotExists(
        "imp_" + TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    try (var sessionOne =
        (DatabaseSessionEmbedded)
            youTrackDB.open("imp_" + TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      var imp =
          new DatabaseImport(
              sessionOne, new ByteArrayInputStream(output.toByteArray()), new MockOutputListener());
      imp.importDatabase();

      var clas1 = sessionOne.getMetadata().getSchema().getClass("Test");
      Assert.assertNotNull(clas1);
      var prop1 = clas1.getProperty("bla");
      Assert.assertNotNull(prop1);
      Assert.assertEquals("something", prop1.getDefaultValue());
    } finally {
      youTrackDB.drop("imp_" + TestSchemaImportExport.class.getSimpleName());
    }
  }

  @Test
  public void testExportImportMultipleInheritance() throws IOException {
    youTrackDB.createIfNotExists(
        TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    var output = new ByteArrayOutputStream();
    try (var db =
        (DatabaseSessionEmbedded)
            youTrackDB.open(TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      var clazz = db.getMetadata().getSchema().createClass("Test");
      clazz.addSuperClass(db.getMetadata().getSchema().getClass("O"));
      clazz.addSuperClass(db.getMetadata().getSchema().getClass("OIdentity"));

      var exp = new DatabaseExport(db, output, new MockOutputListener());
      exp.exportDatabase();
    } finally {
      youTrackDB.drop(TestSchemaImportExport.class.getSimpleName());
    }

    youTrackDB.createIfNotExists(
        "imp_" + TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    try (var sessionOne =
        (DatabaseSessionEmbedded)
            youTrackDB.open("imp_" + TestSchemaImportExport.class.getSimpleName(),
                "admin", "admin")) {
      var imp =
          new DatabaseImport(
              sessionOne, new ByteArrayInputStream(output.toByteArray()), new MockOutputListener());
      imp.importDatabase();

      var clas1 = sessionOne.getMetadata().getSchema().getClass("Test");
      Assert.assertTrue(clas1.isSubClassOf("OIdentity"));
      Assert.assertTrue(clas1.isSubClassOf("O"));
    } finally {
      youTrackDB.drop("imp_" + TestSchemaImportExport.class.getSimpleName());
    }
  }

  private static final class MockOutputListener implements CommandOutputListener {

    @Override
    public void onMessage(String iText) {
    }
  }
}
