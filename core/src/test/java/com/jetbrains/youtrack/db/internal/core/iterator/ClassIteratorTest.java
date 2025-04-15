package com.jetbrains.youtrack.db.internal.core.iterator;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ClassIteratorTest extends DbTestBase {

  private Set<String> names;

  private void createPerson(final String iClassName, final String first) {
    // Create Person document
    session.begin();
    final var personDoc = session.newInstance(iClassName);
    personDoc.setProperty("First", first);
    session.commit();
  }

  public void beforeTest() throws Exception {
    super.beforeTest();

    final Schema schema = session.getMetadata().getSchema();

    // Create Person class
    final var personClass = schema.createClass("Person");
    personClass
        .createProperty("First", PropertyType.STRING)
        .setMandatory(true)
        .setNotNull(true)
        .setMin("1");

    // Insert some data
    names = new HashSet<String>();
    names.add("Adam");
    names.add("Bob");
    names.add("Calvin");
    names.add("Daniel");

    for (var name : names) {
      createPerson("Person", name);
    }
  }

  @Test
  public void testDescendentOrderIteratorWithMultipleCollections() throws Exception {
    var personClass = (SchemaClassInternal) session.getMetadata().getSchema().getClass("Person");

    // empty old collection but keep it attached
    personClass.truncate();
    for (var name : names) {
      createPerson("Person", name);
    }

    // Use descending class iterator.
    final var personIter =
        new RecordIteratorClass(session, "Person", true, false);
    // Explicit iterator loop.
    session.executeInTxBatches(personIter, (s, doc) -> {
      Assert.assertTrue(names.contains(doc.getString("First")));
      Assert.assertTrue(names.remove(doc.getString("First")));
    });

    Assert.assertTrue(names.isEmpty());
  }

  @Test
  public void testMultipleCollections() throws Exception {
    session.getMetadata().getSchema().createClass("PersonMultipleCollections", 4);
    for (var name : names) {
      createPerson("PersonMultipleCollections", name);
    }

    final var personIter = new RecordIteratorClass(session, "PersonMultipleCollections", true, true);

    var docNum = new int[1];

    session.executeInTxBatches(personIter, (s, doc) -> {
      Assert.assertTrue(names.contains(doc.getString("First")));
      Assert.assertTrue(names.remove(doc.getString("First")));
      System.out.printf("Doc %d: %s\n", docNum[0]++, doc);
    });

    Assert.assertTrue(names.isEmpty());
  }
}
