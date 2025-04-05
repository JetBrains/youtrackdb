package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class TestSchemaImportExport extends DbTestBase {

  @Test
  public void testExportImportCustomData() throws IOException {
    context.createIfNotExists(
        TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var db =
        (DatabaseSessionInternal)
            context.open(TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      SchemaClass clazz = db.getMetadata().getSchema().createClass("Test");
      clazz.createProperty(db, "some", PropertyType.STRING);
      clazz.setCustom(db, "testcustom", "test");
      DatabaseExport exp = new DatabaseExport(db, output, new MockOutputListener());
      exp.exportDatabase();
    } finally {
      context.drop(TestSchemaImportExport.class.getSimpleName());
    }

    context.createIfNotExists(
        "imp_" + TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    try (var db1 =
        (DatabaseSessionInternal)
            context.open("imp_" + TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      DatabaseImport imp =
          new DatabaseImport(
              db1, new ByteArrayInputStream(output.toByteArray()), new MockOutputListener());
      imp.importDatabase();
      SchemaClass clas1 = db1.getMetadata().getSchema().getClass("Test");
      Assert.assertNotNull(clas1);
      Assert.assertEquals("test", clas1.getCustom("testcustom"));
    } finally {
      context.drop("imp_" + TestSchemaImportExport.class.getSimpleName());
    }
  }

  @Test
  public void testExportImportDefaultValue() throws IOException {
    context.createIfNotExists(
        TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    try (var db =
        (DatabaseSessionInternal)
            context.open(TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      SchemaClass clazz = db.getMetadata().getSchema().createClass("Test");
      clazz.createProperty(db, "bla", PropertyType.STRING).setDefaultValue(db, "something");
      DatabaseExport exp = new DatabaseExport(db, output, new MockOutputListener());
      exp.exportDatabase();
    } finally {
      context.drop(TestSchemaImportExport.class.getSimpleName());
    }

    context.createIfNotExists(
        "imp_" + TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    try (var db1 =
        (DatabaseSessionInternal)
            context.open("imp_" + TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      DatabaseImport imp =
          new DatabaseImport(
              db1, new ByteArrayInputStream(output.toByteArray()), new MockOutputListener());
      imp.importDatabase();

      SchemaClass clas1 = db1.getMetadata().getSchema().getClass("Test");
      Assert.assertNotNull(clas1);
      SchemaProperty prop1 = clas1.getProperty(db, "bla");
      Assert.assertNotNull(prop1);
      Assert.assertEquals("something", prop1.getDefaultValue());
    } finally {
      context.drop("imp_" + TestSchemaImportExport.class.getSimpleName());
    }
  }

  @Test
  public void testExportImportMultipleInheritance() throws IOException {
    context.createIfNotExists(
        TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var db =
        (DatabaseSessionInternal)
            context.open(TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      SchemaClass clazz = db.getMetadata().getSchema().createClass("Test");
      clazz.addSuperClass(db, db.getMetadata().getSchema().getClass("ORestricted"));
      clazz.addSuperClass(db, db.getMetadata().getSchema().getClass("OIdentity"));

      DatabaseExport exp = new DatabaseExport(db, output, new MockOutputListener());
      exp.exportDatabase();
    } finally {
      context.drop(TestSchemaImportExport.class.getSimpleName());
    }

    context.createIfNotExists(
        "imp_" + TestSchemaImportExport.class.getSimpleName(),
        DatabaseType.MEMORY,
        "admin",
        "admin",
        "admin");
    try (var db1 =
        (DatabaseSessionInternal)
            context.open("imp_" + TestSchemaImportExport.class.getSimpleName(), "admin", "admin")) {
      DatabaseImport imp =
          new DatabaseImport(
              db1, new ByteArrayInputStream(output.toByteArray()), new MockOutputListener());
      imp.importDatabase();
      SchemaClass clas1 = db1.getMetadata().getSchema().getClass("Test");
      Assert.assertTrue(clas1.isSubClassOf(db, "OIdentity"));
      Assert.assertTrue(clas1.isSubClassOf(db, "ORestricted"));
    } finally {
      context.drop("imp_" + TestSchemaImportExport.class.getSimpleName());
    }
  }

  private static final class MockOutputListener implements CommandOutputListener {

    @Override
    public void onMessage(String iText) {
    }
  }
}
