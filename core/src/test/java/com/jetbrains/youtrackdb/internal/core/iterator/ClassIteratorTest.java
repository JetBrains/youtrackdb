package com.jetbrains.youtrackdb.internal.core.iterator;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ClassIteratorTest extends DbTestBase {

  private Set<String> names;

  public void beforeTest() throws Exception {
    super.beforeTest();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person").
            createSchemaProperty("First", PropertyType.STRING).mandatoryAttr(true).
            readOnlyAttr(true).minAttr("1")
    );

    // Insert some data
    names = new HashSet<String>();
    names.add("Adam");
    names.add("Bob");
    names.add("Calvin");
    names.add("Daniel");

    for (var name : names) {
      // Create Person document
      session.begin();
      final var personDoc = session.newInstance("Person");
      personDoc.setProperty("First", name);
      session.commit();
    }
  }

  @Test
  public void testDescendentOrderIteratorWithMultipleCollections() throws Exception {
    var personClass = session.getMetadata().getSlowMutableSchema().getClass("Person");

    // empty old collection but keep it attached
    personClass.truncate();
    for (var name : names) {
      // Create Person document
      session.begin();
      final var personDoc = session.newInstance("Person");
      personDoc.setProperty("First", name);
      session.commit();
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
    session.getMetadata().getSlowMutableSchema().createClass("PersonMultipleCollections");
    for (var name : names) {
      // Create Person document
      session.begin();
      final var personDoc = session.newInstance("PersonMultipleCollections");
      personDoc.setProperty("First", name);
      session.commit();
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
