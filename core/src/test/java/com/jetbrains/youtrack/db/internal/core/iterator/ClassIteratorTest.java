package com.jetbrains.youtrack.db.internal.core.iterator;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection.DefaultClusterSelectionStrategy;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Iterator;
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
    personDoc.field("First", first);

    session.commit();
  }

  public void beforeTest() throws Exception {
    super.beforeTest();

    final Schema schema = session.getMetadata().getSchema();

    // Create Person class
    final var personClass = schema.createClass("Person");
    personClass
        .createProperty(session, "First", PropertyType.STRING)
        .setMandatory(session, true)
        .setNotNull(session, true)
        .setMin(session, "1");

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
  public void testDescendentOrderIteratorWithMultipleClusters() throws Exception {
    var personClass = (SchemaClassInternal) session.getMetadata().getSchema().getClass("Person");

    // empty old cluster but keep it attached
    personClass.truncate(session);

    // reload the data in a new 'test' cluster
    var testClusterId = session.addCluster("test");
    personClass.addClusterId(session, testClusterId);
    personClass.setClusterSelection(session, new DefaultClusterSelectionStrategy());

    for (var name : names) {
      createPerson("Person", name);
    }

    // Use descending class iterator.
    final RecordIteratorClass<EntityImpl> personIter =
        new RecordIteratorClassDescendentOrder<EntityImpl>(session, session, "Person", true);

    personIter.setRange(null, null); // open range

    var docNum = new int[1];
    // Explicit iterator loop.
    session.executeInTxBatches((Iterator<EntityImpl>) personIter, (s, doc) -> {
      Assert.assertTrue(names.contains(doc.getString("First")));
      Assert.assertTrue(names.remove(doc.getString("First")));
      System.out.printf("Doc %d: %s\n", docNum[0]++, doc);
    });

    Assert.assertTrue(names.isEmpty());
  }

  @Test
  public void testMultipleClusters() throws Exception {
    session.getMetadata().getSchema().createClass("PersonMultipleClusters", 4);
    for (var name : names) {
      createPerson("PersonMultipleClusters", name);
    }

    final var personIter =
        new RecordIteratorClass<EntityImpl>(session, "PersonMultipleClusters", true);

    var docNum = new int[1];

    session.executeInTxBatches((Iterator<EntityImpl>) personIter, (s, doc) -> {
      Assert.assertTrue(names.contains(doc.getString("First")));
      Assert.assertTrue(names.remove(doc.getString("First")));
      System.out.printf("Doc %d: %s\n", docNum[0]++, doc);
    });

    Assert.assertTrue(names.isEmpty());
  }
}
