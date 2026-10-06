package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.nio.file.Path;
import org.junit.Test;

/** Run explicitly to produce a stored importer compatibility fixture. Not part of the test suite. */
public class ImportCompatibilityDumpGenerator extends DbTestBase {

  /** Both versions have the same records except that version 15 includes a dotted class. */
  @Test
  public void generateRequestedFormat() throws Exception {
    var format = Integer.parseInt(System.getProperty("importCompat.format"));
    if (format != 14 && format != 15) {
      throw new IllegalArgumentException("Only format 14 or 15 can use this fixture recipe");
    }
    assertEquals("Use the exporter from the requested revision", format,
        DatabaseExport.EXPORTER_VERSION);
    var output = Path.of(System.getProperty("importCompat.output"));

    var schema = session.getMetadata().getSchema();
    var parent = schema.createClass("CompatParent");
    parent.createProperty("name", PropertyType.STRING);
    parent.createProperty("age", PropertyType.INTEGER);
    parent.createProperty("address", PropertyType.EMBEDDED);
    parent.createProperty("peer", PropertyType.LINK, parent);
    parent.createIndex("CompatParent.name", INDEX_TYPE.UNIQUE, "name");
    parent.createIndex("CompatParent.age", INDEX_TYPE.NOTUNIQUE, "age");
    schema.createClass("CompatChild", parent);
    if (format == 15) {
      schema.createClass("Compat.Note").createProperty("label", PropertyType.STRING);
    }

    session.executeInTx(tx -> {
      var alice = session.newEntity("CompatParent");
      alice.setString("name", "alice");
      alice.setInt("age", 30);
      var address = session.newEmbeddedEntity();
      address.setString("city", "Oslo");
      alice.setProperty("address", address, PropertyType.EMBEDDED);

      var bob = session.newEntity("CompatChild");
      bob.setString("name", "bob");
      bob.setInt("age", 24);
      bob.setLink("peer", alice);
      if (format == 15) {
        session.newEntity("Compat.Note").setString("label", "synthetic");
      }
    });

    new DatabaseExport(session, output.toString(), text -> {
    }).setOptions(" -includeManualIndexes=false").exportDatabase();
  }
}
