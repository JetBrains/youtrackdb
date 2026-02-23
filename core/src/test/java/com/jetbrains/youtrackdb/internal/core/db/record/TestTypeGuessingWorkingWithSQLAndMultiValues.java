package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.query.BasicResult;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests property type guessing when working with SQL queries and multi-value fields.
 */
public class TestTypeGuessingWorkingWithSQLAndMultiValues extends DbTestBase {

  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.computeScript(
            "sql",
            """
                create class Address abstract;
                create property Address.street String;
                create property Address.city String;
                create class Client;
                create property Client.name String;
                create property Client.phones embeddedSet String;
                create property Client.addresses embeddedList Address;""")
        .close();
  }

  @Test
  public void testLinkedValue() {
    session.begin();
    try (var result =
        session.computeScript(
            "sql",
            "let res = insert into client set name = 'James Bond', phones = ['1234',"
                + " '34567'], addresses = [{'@class':'Address','city':'Shanghai', 'zip':'3999'},"
                + " {'@class':'Address','city':'New York', 'street':'57th Ave'}]\n"
                + ";update client set addresses = addresses ||"
                + " [{'@type':'d','@class':'Address','city':'London', 'zip':'67373'}];"
                + " return $res")) {
      Assert.assertTrue(result.hasNext());
      var doc = result.next();

      Collection<EntityImpl> addresses = doc.getProperty("addresses");
      Assert.assertEquals(3, addresses.size());
      for (var a : addresses) {
        Assert.assertEquals("Address", a.getProperty("@class"));
      }
    }
    session.commit();

    session.begin();
    try (var resultSet =
        session.execute(
            "update client set addresses = addresses || [{'city':'London', 'zip':'67373'}] return"
                + " after")) {
      Assert.assertTrue(resultSet.hasNext());

      var result = resultSet.next();

      Collection<BasicResult> addresses = result.getProperty("addresses");
      Assert.assertEquals(4, addresses.size());

      for (var a : addresses) {
        Assert.assertEquals("Address", a.getProperty("@class"));
      }
    }
    session.commit();
  }
}
