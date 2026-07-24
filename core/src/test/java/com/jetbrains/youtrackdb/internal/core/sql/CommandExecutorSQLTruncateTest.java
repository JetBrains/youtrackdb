package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import java.io.IOException;
import org.junit.Test;

public class CommandExecutorSQLTruncateTest extends DbTestBase {

  @Test
  public void testTruncatePlain() {
    var vcl = session.getMetadata().getSchema().createClass("A");
    session.getMetadata().getSchema().createClass("ab", vcl);

    session.begin();
    session.newEntity("A");
    session.commit();

    session.begin();
    session.newEntity("ab");
    session.commit();

    session.begin();
    var ret = session.execute("truncate class A ");
    assertEquals(1L, (long) ret.next().getProperty("count"));
    session.commit();
  }

  @Test
  public void testTruncateAPI() throws IOException {
    session.getMetadata().getSchema().createClass("A");

    session.begin();
    session.newEntity("A");
    // Pull an existing internal record into the transaction before the polymorphic truncate
    // below runs. The record used to be pinned as #1:3 (a genesis security record under the
    // pre-Track-8 fresh-DB layout); since the $blob* collections moved to the storage-birth
    // slots 1..N, the target is resolved dynamically so the test is layout-independent.
    try (var result = session.query("select from OSecurityPolicy limit 1")) {
      session.load(result.next().getIdentity());
    }
    session.commit();

    session.begin();
    session.getMetadata().getSchema().getClasses().stream()
        .filter(oClass -> !oClass.getName().startsWith("OSecurity")) //
        .forEach(
            oClass -> {
              if (((SchemaClassInternal) oClass).count(session) > 0) {
                session.execute("truncate class " + oClass.getName() + " POLYMORPHIC UNSAFE")
                    .close();
              }
            });
    session.commit();
  }

  @Test
  public void testTruncatePolimorphic() {
    var vcl = session.getMetadata().getSchema().createClass("A");
    session.getMetadata().getSchema().createClass("ab", vcl);

    session.begin();
    session.newEntity("A");
    session.commit();

    session.begin();
    session.newEntity("ab");
    session.commit();

    session.begin();
    try (var res = session.execute("truncate class A POLYMORPHIC")) {
      assertEquals(1L, (long) res.next().getProperty("count"));
      assertEquals(1L, (long) res.next().getProperty("count"));
    }
    session.commit();
  }
}
