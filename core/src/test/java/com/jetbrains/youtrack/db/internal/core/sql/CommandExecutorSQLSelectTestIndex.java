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

package com.jetbrains.youtrack.db.internal.core.sql;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import org.junit.Test;

public class CommandExecutorSQLSelectTestIndex extends BaseMemoryInternalDatabase {

  @Test
  public void testIndexSqlEmbeddedList() {

    session.command("create class Foo").close();
    session.command("create property Foo.bar EMBEDDEDLIST STRING").close();
    session.command("create index Foo.bar on Foo (bar) NOTUNIQUE").close();
    session.executeInTx(() -> {
      session.command("insert into Foo set bar = ['yep']").close();
    });
    var results = session.query("select from Foo where bar = 'yep'");
    assertEquals(results.stream().count(), 1);

    final var index = session.getMetadata().getIndexManagerInternal().getIndex(session, "Foo.bar");
    assertEquals(index.size(session), 1);
  }

  @Test
  public void testIndexOnHierarchyChange() {
    // issue #5743

    session.command("CREATE CLASS Main ABSTRACT").close();
    session.command("CREATE PROPERTY Main.uuid String").close();
    session.command("CREATE INDEX Main.uuid UNIQUE_HASH_INDEX").close();
    session.command("CREATE CLASS Base EXTENDS Main ABSTRACT").close();
    session.command("CREATE CLASS Derived EXTENDS Main").close();
    session.command("INSERT INTO Derived SET uuid='abcdef'").close();
    session.command("ALTER CLASS Derived SUPERCLASSES Base").close();

    var results = session.query("SELECT * FROM Derived WHERE uuid='abcdef'");
    assertEquals(results.stream().count(), 1);
  }

  @Test
  public void testListContainsField() {
    session.command("CREATE CLASS Foo").close();
    session.command("CREATE PROPERTY Foo.name String").close();

    session.executeInTx(() -> {
      session.command("INSERT INTO Foo SET name = 'foo'").close();
    });

    var result = session.query("SELECT * FROM Foo WHERE ['foo', 'bar'] CONTAINS name");
    assertEquals(result.stream().count(), 1);

    result = session.query("SELECT * FROM Foo WHERE name IN ['foo', 'bar']");
    assertEquals(result.stream().count(), 1);

    session.command("CREATE INDEX Foo.name UNIQUE_HASH_INDEX").close();

    result = session.query("SELECT * FROM Foo WHERE ['foo', 'bar'] CONTAINS name");
    assertEquals(result.stream().count(), 1);

    result = session.query("SELECT * FROM Foo WHERE name IN ['foo', 'bar']");
    assertEquals(result.stream().count(), 1);

    result = session.query("SELECT * FROM Foo WHERE name IN ['foo', 'bar']");
    assertEquals(result.stream().count(), 1);
  }
}
