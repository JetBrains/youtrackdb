package com.jetbrains.youtrackdb.internal.core.sql.update;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Map;
import org.junit.Test;

public class SQLUpdateMapTest extends DbTestBase {

  @Test
  public void testMapPut() {

    EntityImpl ret;
    session.execute("create class vRecord").close();
    session.execute("create property vRecord.attrs EMBEDDEDMAP ").close();

    session.begin();
    try (var rs = session.execute("insert into vRecord (title) values('first record')")) {
      ret = (EntityImpl) rs.next().asRecord();
    }

    session.execute("insert into vRecord (title) values('second record')").close();
    session.commit();

    session.begin();
    session.execute(
            "update " + ret.getIdentity() + " set attrs =  {'test1':'first test' }")
        .close();
    session.commit();
    reOpen("admin", "adminpwd");

    session.begin();
    session.execute("update " + ret.getIdentity() + " set attrs['test'] = 'test value' ").close();
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    ret = activeTx.load(ret);
    assertEquals(2, ((Map) ret.getProperty("attrs")).size());
    assertEquals("test value", ((Map) ret.getProperty("attrs")).get("test"));
    assertEquals("first test", ((Map) ret.getProperty("attrs")).get("test1"));
    session.commit();
  }
}
