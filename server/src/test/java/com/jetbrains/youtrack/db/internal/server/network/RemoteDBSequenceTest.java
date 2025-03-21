package com.jetbrains.youtrack.db.internal.server.network;

import static org.junit.Assert.assertNotEquals;

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import org.junit.Test;

/**
 *
 */
public class RemoteDBSequenceTest extends BaseServerMemoryDatabase {

  @Test
  public void testSequences() {
    var database = db;
    database.execute("CREATE CLASS SV extends V").close();
    database.execute("CREATE SEQUENCE seqCounter TYPE ORDERED").close();
    database.execute("CREATE PROPERTY SV.uniqueID Long").close();
    database.execute("CREATE PROPERTY SV.testID Long").close();
    database.execute("ALTER PROPERTY SV.uniqueID NOTNULL true").close();
    database.execute("ALTER PROPERTY SV.uniqueID MANDATORY true").close();
    database.execute("ALTER PROPERTY SV.uniqueID READONLY true").close();
    database
        .execute("ALTER PROPERTY SV.uniqueID DEFAULT 'sequence(\"seqCounter\").next()'")
        .close();
    database.execute("CREATE CLASS CV1 extends SV").close();
    database.execute("CREATE CLASS CV2 extends SV").close();
    database.execute("CREATE INDEX uniqueID ON SV (uniqueID) UNIQUE").close();
    database.execute("CREATE INDEX testid ON SV (testID) UNIQUE").close();
    database.reload();

    database.begin();
    var doc = ((EntityImpl) db.newVertex("CV1"));
    doc.setProperty("testID", 1);
    var doc1 = ((EntityImpl) db.newVertex("CV1"));
    doc1.setProperty("testID", 1);
    assertNotEquals(doc1.getProperty("uniqueID"), doc.getProperty("uniqueID"));
  }
}
