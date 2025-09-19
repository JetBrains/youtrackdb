package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import org.junit.Test;

public class CommandExecutorSQLTruncateTest extends DbTestBase {

  @Test
  public void testTruncatePlain() {
    var vcl = session.getMetadata().getSchema().createClass("A");
    session.getMetadata().getSchema().createClass("ab", vcl);

    session.begin();
    var doc = (EntityImpl) session.newEntity("A");
    session.commit();

    session.begin();
    doc = (EntityImpl) session.newEntity("ab");
    session.commit();

    var ret = session.execute("truncate class A ");
    assertEquals(1L, (long) ret.next().getProperty("count"));
  }

  @Test
  public void testTruncateAPI() throws IOException {
    session.getMetadata().getSchema().createClass("A");

    session.begin();
    var doc = (EntityImpl) session.newEntity("A");
    var record = session.load(new RecordId(1,3));
    session.commit();

    session.getMetadata().getSchema().getClasses().stream()
        .filter(oClass -> !oClass.getName().startsWith("OSecurity")) //
        .forEach(
            oClass -> {
              if (oClass.count(session) > 0) {
                session.execute("truncate class " + oClass.getName() + " POLYMORPHIC UNSAFE")
                    .close();
              }
            });
  }

  @Test
  public void testTruncatePolimorphic() {
    var vcl = session.getMetadata().getSchema().createClass("A");
    session.getMetadata().getSchema().createClass("ab", vcl);

    session.begin();
    var doc = (EntityImpl) session.newEntity("A");
    session.commit();

    session.begin();
    doc = (EntityImpl) session.newEntity("ab");
    session.commit();

    try (var res = session.execute("truncate class A POLYMORPHIC")) {
      assertEquals(1L, (long) res.next().getProperty("count"));
      assertEquals(1L, (long) res.next().getProperty("count"));
    }
  }
}
