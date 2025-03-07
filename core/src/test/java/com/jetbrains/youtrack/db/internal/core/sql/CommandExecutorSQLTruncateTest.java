package com.jetbrains.youtrack.db.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.IOException;
import org.junit.Test;

public class CommandExecutorSQLTruncateTest extends DbTestBase {

  @Test
  public void testTruncatePlain() {
    SchemaClass vcl = db.getMetadata().getSchema().createClass("A");
    db.getMetadata().getSchema().createClass("ab", vcl);

    db.begin();
    EntityImpl doc = new EntityImpl("A");
    db.save(doc);
    db.commit();

    db.begin();
    doc = new EntityImpl("ab");
    db.save(doc);
    db.commit();

    ResultSet ret = db.command("truncate class A ");
    assertEquals((long) ret.next().getProperty("count"), 1L);
  }

  @Test
  public void testTruncateAPI() throws IOException {
    db.getMetadata().getSchema().createClass("A");

    db.begin();
    EntityImpl doc = new EntityImpl("A");
    db.save(doc);
    db.commit();

    db.getMetadata().getSchema().getClassesRefs(db).keySet().stream()
        .filter(className -> !className.startsWith("OSecurity"))
        .forEach(
            className -> {
              SchemaClass oClass = db.getMetadata().getSchema().getClass(className);
              if (((SchemaClassInternal) oClass).count(db) > 0) {
                db.command("truncate class " + oClass.getName() + " POLYMORPHIC UNSAFE").close();
              }
            });
  }

  @Test
  public void testTruncatePolimorphic() {
    SchemaClass vcl = db.getMetadata().getSchema().createClass("A");
    db.getMetadata().getSchema().createClass("ab", vcl);

    db.begin();
    EntityImpl doc = new EntityImpl("A");
    db.save(doc);
    db.commit();

    db.begin();
    doc = new EntityImpl("ab");
    db.save(doc);
    db.commit();

    try (ResultSet res = db.command("truncate class A POLYMORPHIC")) {
      assertEquals((long) res.next().getProperty("count"), 1L);
      assertEquals((long) res.next().getProperty("count"), 1L);
    }
  }
}
