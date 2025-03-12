package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class IndexConcurrentCommitTest extends BaseDBTest {

  @Parameters(value = "remote")
  public IndexConcurrentCommitTest(boolean remote) {
    super(remote);
  }

  public void testConcurrentUpdate() {
    var personClass = session.getMetadata().getSchema().createClass("Person");
    personClass.createProperty("ssn", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
    personClass.createProperty("name", PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    try {
      // Transaction 1
      session.begin();

      // Insert two people in a transaction
      var person1 = ((EntityImpl) session.newEntity("Person"));
      person1.field("name", "John Doe");
      person1.field("ssn", "111-11-1111");

      var person2 = ((EntityImpl) session.newEntity("Person"));
      person2.field("name", "Jane Doe");
      person2.field("ssn", "222-22-2222");

      // Commit
      session.commit();

      // Ensure that the people made it in correctly
      final var result1 = session.query("select from Person");
      while (result1.hasNext()) {
        System.out.println(result1.next());
      }

      // Transaction 2
      session.begin();

      // Update the ssn for the second person
      person2.field("ssn", "111-11-1111");

      // Update the ssn for the first person
      person1.field("ssn", "222-22-2222");

      System.out.println("To be committed:");
      System.out.println(person1);
      System.out.println(person2);
      // Commit - We get a transaction failure!
      session.commit();

      System.out.println("Success!");
    } catch (IndexException e) {
      System.out.println("Exception: " + e);
      session.rollback();
    }

    final var result2 = session.command("select from Person");
    System.out.println("After transaction 2");
    while (result2.hasNext()) {
      System.out.println(result2.next());
    }
  }
}
