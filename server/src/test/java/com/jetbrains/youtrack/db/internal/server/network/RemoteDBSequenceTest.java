package com.jetbrains.youtrack.db.internal.server.network;

import com.jetbrains.youtrack.db.internal.server.BaseServerMemoryDatabase;
import java.util.HashSet;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class RemoteDBSequenceTest extends BaseServerMemoryDatabase {
  @Test
  public void testSequences() {
    var database = session;
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

    database.execute("CREATE INDEX testID1 ON CV1 (testID) UNIQUE").close();
    database.execute("CREATE INDEX testID2 ON CV2 (testID) UNIQUE").close();

    database.executeSQLScript("""
        begin;
        let $v1 = create vertex CV1 set testID = 1;
        let $v2 = create vertex CV2 set testID = 1;
        commit;
        """);

    var result =
        database.query("select unionAll(uniqueID) as ids from SV")
            .findFirst(
                remoteResult -> remoteResult.getEmbeddedList("ids"));

    Assert.assertEquals(2, new HashSet<>(result).size());
  }
}
