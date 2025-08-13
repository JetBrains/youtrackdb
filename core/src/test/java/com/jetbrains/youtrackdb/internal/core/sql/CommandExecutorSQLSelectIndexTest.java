/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import org.junit.Test;

public class CommandExecutorSQLSelectIndexTest extends BaseMemoryInternalDatabase {

  @Test
  public void testIndexSqlEmbeddedList() {

    session.execute("create class Foo").close();
    session.execute("create property Foo.bar EMBEDDEDLIST STRING").close();
    session.execute("create index Foo.bar on Foo (bar) NOTUNIQUE").close();
    session.executeInTx(transaction -> {
      session.execute("insert into Foo set bar = ['yep']").close();
    });

    var results = session.query("select from Foo where bar = 'yep'");
    assertEquals(1, results.stream().count());
    results.close();

    final var index = session.getSharedContext().getIndexManager().getIndex("Foo.bar");
    assertEquals(1, index.size(session));
  }

  @Test
  public void testIndexOnHierarchyChange() {
    // issue #5743

    session.execute("CREATE CLASS Main ABSTRACT").close();
    session.execute("CREATE PROPERTY Main.uuid String").close();
    session.execute("CREATE INDEX Main.uuid UNIQUE").close();
    session.execute("CREATE CLASS Base EXTENDS Main ABSTRACT").close();
    session.execute("CREATE CLASS Derived EXTENDS Main").close();
    session.execute("BEGIN");
    session.execute("INSERT INTO Derived SET uuid='abcdef'").close();
    session.command("COMMIT");
    session.execute("DROP INDEX Main.uuid").close();
    session.command("ALTER CLASS Derived SUPERCLASSES Base");
    session.command("CREATE INDEX Main.uuid UNIQUE");

    session.begin();
    var results = session.query("SELECT * FROM Derived WHERE uuid='abcdef'");
    assertEquals(1, results.stream().count());
    results.close();
    session.commit();
  }

  @Test
  public void testListContainsField() {
    session.execute("CREATE CLASS Foo").close();
    session.execute("CREATE PROPERTY Foo.name String").close();

    session.executeInTx(transaction -> {
      session.execute("INSERT INTO Foo SET name = 'foo'").close();
    });

    session.executeInTx(tx -> {
      var result = session.query("SELECT * FROM Foo WHERE ['foo', 'bar'] CONTAINS name");
      assertEquals(1, result.stream().count());
      result.close();

      result = session.query("SELECT * FROM Foo WHERE name IN ['foo', 'bar']");
      assertEquals(1, result.stream().count());
      result.close();
    });
    session.execute("CREATE INDEX Foo.name UNIQUE").close();

    var result = session.query("SELECT * FROM Foo WHERE ['foo', 'bar'] CONTAINS name");
    assertEquals(1, result.stream().count());
    result.close();

    result = session.query("SELECT * FROM Foo WHERE name IN ['foo', 'bar']");
    assertEquals(1, result.stream().count());
    result.close();

    result = session.query("SELECT * FROM Foo WHERE name IN ['foo', 'bar']");
    assertEquals(1, result.stream().count());
    result.close();
  }
}
