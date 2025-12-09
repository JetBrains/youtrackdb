package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.Assert;
import org.junit.Test;

public class SQLFunctionIndexKeySizeTest extends DbTestBase {

  @Test
  public void test() {
    var clazz = session.getMetadata().getSchema().createClass("Test");
    clazz.createProperty("name", PropertyType.STRING);
    session.execute("create index testindex on  Test (name) notunique").close();

    session.begin();
    session.execute("insert into Test set name = 'a'").close();
    session.execute("insert into Test set name = 'b'").close();
    session.commit();

    try (var rs = session.query("select indexKeySize('testindex') as foo")) {
      Assert.assertTrue(rs.hasNext());
      var item = rs.next();
      Assert.assertEquals((Object) 2L, item.getProperty("foo"));
      Assert.assertFalse(rs.hasNext());
    }
  }
}
