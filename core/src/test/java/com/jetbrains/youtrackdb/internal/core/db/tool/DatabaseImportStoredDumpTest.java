package com.jetbrains.youtrackdb.internal.core.db.tool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

/** Imports committed exporter output rather than exporting a fixture with the current code. */
public class DatabaseImportStoredDumpTest extends DbTestBase {

  // Keep this list in sync with the format-specific content tests below.
  private static final Set<Integer> CONTENT_TESTED_FORMATS = Set.of(14, 15);

  /** Format 14 retains schema, indexes, values, and links without dotted class names. */
  @Test
  public void importsFormat14() throws Exception {
    assertStoredDump(14);
  }

  /** Format 15 also retains a dotted class name and its record. */
  @Test
  public void importsFormat15() throws Exception {
    assertStoredDump(15);
  }

  /** The current format must have a matching dump and a content-checking import test. */
  @Test
  public void currentExporterVersionHasStoredDump() throws Exception {
    var format = DatabaseExport.EXPORTER_VERSION;
    assertTrue("Add a content-checking import test for format " + format,
        CONTENT_TESTED_FORMATS.contains(format));
    assertStoredDump(format);
  }

  private void assertStoredDump(int format) throws Exception {
    var path = "/import-compat/format-" + format + ".json.gz";
    try (var raw = getClass().getResourceAsStream(path)) {
      assertNotNull("Missing stored dump: " + path, raw);
      var dump = new ObjectMapper().readTree(new GZIPInputStream(raw));
      assertEquals("the file must be from its named exporter version", format,
          dump.path("info").path("exporter-version").asInt());
      assertEquals("the fixture has no broken links", 0, dump.path("brokenRids").size());
    }
    try (var input = getClass().getResourceAsStream(path)) {
      assertNotNull("Missing stored dump: " + path, input);
      new DatabaseImport(session, input, text -> {
      }).importDatabase();
    }

    var schema = session.getMetadata().getSchema();
    var parent = schema.getClass("CompatParent");
    var child = schema.getClass("CompatChild");
    assertNotNull("parent class", parent);
    assertNotNull("child class", child);
    assertTrue("child inherits parent", child.getSuperClasses().stream()
        .anyMatch(superClass -> "CompatParent".equals(superClass.getName())));
    assertEquals(PropertyType.STRING, parent.getProperty("name").getType());
    assertEquals(PropertyType.INTEGER, parent.getProperty("age").getType());
    assertEquals(PropertyType.EMBEDDED, parent.getProperty("address").getType());
    assertEquals(PropertyType.LINK, parent.getProperty("peer").getType());
    assertEquals("CompatParent", parent.getProperty("peer").getLinkedClass().getName());
    var indexes = session.getSharedContext().getIndexManager();
    var nameIndex = indexes.getIndex("CompatParent.name");
    assertNotNull("unique index", nameIndex);
    assertEquals("UNIQUE", nameIndex.getType());
    assertEquals("CompatParent", nameIndex.getDefinition().getClassName());
    assertEquals(List.of("name"), nameIndex.getDefinition().getFieldsToIndex());
    assertArrayEquals(new PropertyTypeInternal[] {PropertyTypeInternal.STRING},
        nameIndex.getDefinition().getTypes());
    var ageIndex = indexes.getIndex("CompatParent.age");
    assertNotNull("non-unique index", ageIndex);
    assertEquals("NOTUNIQUE", ageIndex.getType());
    assertEquals("CompatParent", ageIndex.getDefinition().getClassName());
    assertEquals(List.of("age"), ageIndex.getDefinition().getFieldsToIndex());
    assertArrayEquals(new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER},
        ageIndex.getDefinition().getTypes());
    if (format == 15) {
      var dotted = schema.getClass("Compat.Note");
      assertNotNull("dotted class", dotted);
      assertEquals(PropertyType.STRING, dotted.getProperty("label").getType());
      assertFalse("dotted class must not be renamed", schema.existsClass("Compat_Note"));
    } else {
      assertFalse("format 14 contains no dotted class", schema.existsClass("Compat.Note"));
    }

    session.executeInTx(tx -> {
      assertEquals("one direct parent", 1, session.countClass("CompatParent", false));
      assertEquals("one child", 1, session.countClass("CompatChild"));
      try (var parents = session.browseClass("CompatParent", false);
          var children = session.browseClass("CompatChild")) {
        assertTrue("parent record", parents.hasNext());
        var alice = parents.next();
        assertEquals("alice", alice.getString("name"));
        assertEquals(Integer.valueOf(30), alice.getInt("age"));
        assertEquals("Oslo", alice.getEntity("address").getString("city"));
        assertFalse("exactly one parent record", parents.hasNext());

        assertTrue("child record", children.hasNext());
        var bob = children.next();
        assertEquals("bob", bob.getString("name"));
        assertEquals(Integer.valueOf(24), bob.getInt("age"));
        assertEquals("CompatParent", bob.getEntity("peer").getSchemaClassName());
        assertEquals("alice", bob.getEntity("peer").getString("name"));
        assertEquals(alice.getIdentity(), bob.getEntity("peer").getIdentity());
        assertFalse("exactly one child record", children.hasNext());
      }
      if (format == 15) {
        assertEquals("one dotted class record", 1, session.countClass("Compat.Note"));
        try (var notes = session.browseClass("Compat.Note")) {
          assertEquals("synthetic", notes.next().getString("label"));
          assertFalse("exactly one dotted record", notes.hasNext());
        }
      }
    });
  }
}
