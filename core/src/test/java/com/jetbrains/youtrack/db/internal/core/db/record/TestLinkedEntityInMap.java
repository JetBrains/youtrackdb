package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class TestLinkedEntityInMap extends DbTestBase {

  @Test
  public void testLinkedValue() {
    session.getMetadata().getSchema().createClass("PersonTest");
    session.begin();
    session.execute("delete from PersonTest").close();
    session.commit();

    session.begin();
    var jaimeDoc = (EntityImpl) session.newEntity("PersonTest");
    jaimeDoc.setProperty("name", "jaime");

    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    jaimeDoc = activeTx1.load(jaimeDoc);
    var tyrionDoc = (EntityImpl) session.newEntity("PersonTest");
    tyrionDoc.updateFromJSON(
        "{\"@type\":\"d\",\"name\":\"tyrion\",\"emergency_contact\":[{\"@embedded\":true,\"relationship\":\"brother\",\"contact\":"
            + jaimeDoc.toJSON()
            + "}]}");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    tyrionDoc = activeTx.load(tyrionDoc);
    List<Entity> res = tyrionDoc.getProperty("emergency_contact");
    var doc = res.getFirst();
    Assert.assertTrue(doc.getLink("contact").isPersistent());
    session.commit();

    reOpen("admin", "adminpwd");
    session.begin();
    try (var result = session.query("select from " + tyrionDoc.getIdentity())) {
      res = result.next().getEmbeddedList("emergency_contact");
      doc = res.getFirst();
      Assert.assertTrue(doc.getLink("contact").isPersistent());
    }
    session.commit();
  }
}
