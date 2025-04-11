package com.jetbrains.youtrack.db.internal.core.sql.parser;

import static org.junit.Assert.fail;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class DeleteStatementTest extends DbTestBase {

  protected SimpleNode checkRightSyntax(String query) {
    return checkSyntax(query, true);
  }

  protected SimpleNode checkWrongSyntax(String query) {
    return checkSyntax(query, false);
  }

  protected SimpleNode checkSyntax(String query, boolean isCorrect) {
    var osql = getParserFor(query);
    try {
      SimpleNode result = osql.parse();
      if (!isCorrect) {
        fail();
      }
      return result;
    } catch (Exception e) {
      if (isCorrect) {
        e.printStackTrace();
        fail();
      }
    }
    return null;
  }

  @Test
  public void deleteFromSubqueryWithWhereTest() {

    session.execute("create class Foo").close();
    session.execute("create class Bar").close();

    session.begin();
    final var doc1 = ((EntityImpl) session.newEntity("Foo"));
    doc1.setProperty("k", "key1");
    final var doc2 = ((EntityImpl) session.newEntity("Foo"));
    doc2.setProperty("k", "key2");
    final var doc3 = ((EntityImpl) session.newEntity("Foo"));
    doc3.setProperty("k", "key3");

    List<Identifiable> list = new ArrayList<>();
    list.add(doc1);
    list.add(doc2);
    list.add(doc3);
    session.newEntity("Bar").newLinkList("arr", list);

    session.commit();

    session.begin();
    session.execute("delete from (select expand(arr) from Bar) where k = 'key2'").close();
    session.commit();

    session.begin();
    try (var result = session.query("select from Foo")) {
      Assert.assertNotNull(result);
      var count = 0;
      while (result.hasNext()) {
        var doc = result.next();
        Assert.assertNotEquals(doc.getProperty("k"), "key2");
        count += 1;
      }
      Assert.assertEquals(count, 2);
    }
    session.commit();
  }

  protected YouTrackDBSql getParserFor(String string) {
    InputStream is = new ByteArrayInputStream(string.getBytes());
    var osql = new YouTrackDBSql(is);
    return osql;
  }
}
