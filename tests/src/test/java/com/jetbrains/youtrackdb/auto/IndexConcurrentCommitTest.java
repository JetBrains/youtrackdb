package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.testng.annotations.Test;

@Test
public class IndexConcurrentCommitTest extends BaseDBTest {

  public void testConcurrentUpdate() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("Person",
            __.createSchemaProperty("ssn", PropertyType.STRING).createPropertyIndex(
                YTDBSchemaIndex.IndexType.UNIQUE),
            __.createSchemaProperty("name", PropertyType.STRING).createPropertyIndex(
                YTDBSchemaIndex.IndexType.NOT_UNIQUE)
        )
    );


    try {
      // Transaction 1
      session.begin();

      // Insert two people in a transaction
      var person1 = ((EntityImpl) session.newEntity("Person"));
      person1.setProperty("name", "John Doe");
      person1.setProperty("ssn", "111-11-1111");

      var person2 = ((EntityImpl) session.newEntity("Person"));
      person2.setProperty("name", "Jane Doe");
      person2.setProperty("ssn", "222-22-2222");

      // Commit
      session.commit();

      session.begin();
      // Ensure that the people made it in correctly
      final var result1 = session.query("select from Person");
      while (result1.hasNext()) {
        System.out.println(result1.next());
      }
      result1.close();
      session.commit();

      // Transaction 2
      session.begin();
      person1 = session.load(person1.getIdentity());
      person2 = session.load(person2.getIdentity());

      // Update the ssn for the second person
      person2.setProperty("ssn", "111-11-1111");

      // Update the ssn for the first person
      person1.setProperty("ssn", "222-22-2222");

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

    session.begin();
    final var result2 = session.execute("select from Person");
    System.out.println("After transaction 2");
    while (result2.hasNext()) {
      System.out.println(result2.next());
    }
    session.commit();
  }
}
