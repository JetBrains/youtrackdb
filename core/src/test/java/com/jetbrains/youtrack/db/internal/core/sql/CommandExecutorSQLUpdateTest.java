/*
 *
 *  *  Copyright YouTrackDB
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.command.script.CommandScript;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Ignore;
import org.junit.Test;

public class CommandExecutorSQLUpdateTest extends DbTestBase {

  @Test
  public void testUpdateRemoveAll() throws Exception {

    session.command("CREATE class company").close();
    session.command("CREATE property company.name STRING").close();
    session.command("CREATE class employee").close();
    session.command("CREATE property employee.name STRING").close();
    session.command("CREATE property company.employees LINKSET employee").close();

    session.begin();
    session.command("INSERT INTO company SET name = 'MyCompany'").close();
    session.commit();

    final var r = session.query("SELECT FROM company").findFirst(Result::asEntity);

    session.begin();
    session.executeInTx(() -> {
      session.command("INSERT INTO employee SET name = 'Philipp'").close();
      session.command("INSERT INTO employee SET name = 'Selma'").close();
      session.command("INSERT INTO employee SET name = 'Thierry'").close();
      session.command("INSERT INTO employee SET name = 'Linn'").close();
    });

    session.command("UPDATE company set employees = (SELECT FROM employee)").close();
    session.commit();

    session.executeInTx(() ->
        assertEquals(((Set) session.bindToSession(r).getProperty("employees")).size(), 4)
    );

    session.executeInTx(() ->
        session.command(
                "UPDATE company REMOVE employees = (SELECT FROM employee WHERE name = 'Linn') WHERE"
                    + " name = 'MyCompany'")
            .close()
    );

    session.executeInTx(() ->
        assertEquals(((Set) session.bindToSession(r).getProperty("employees")).size(), 3)
    );
  }

  @Test
  public void testUpdateContent() throws Exception {
    session.begin();
    session.command("insert into V (name) values ('bar')").close();
    session.command("UPDATE V content {\"value\":\"foo\"}").close();
    session.commit();

    try (var result = session.query("select from V")) {
      var doc = result.next();
      assertEquals(doc.getProperty("value"), "foo");
    }
  }

  @Test
  public void testUpdateContentParse() throws Exception {
    session.begin();
    session.command("insert into V (name) values ('bar')").close();
    session.command("UPDATE V content {\"value\":\"foo\\\\\"}").close();
    session.commit();

    try (var result = session.query("select from V")) {
      assertEquals(result.next().getProperty("value"), "foo\\");
    }

    session.begin();
    session.command("UPDATE V content {\"value\":\"foo\\\\\\\\\"}").close();

    try (var result = session.query("select from V")) {
      assertEquals(result.next().getProperty("value"), "foo\\\\");
    }
    session.commit();
  }

  @Test
  public void testUpdateMergeWithIndex() {
    session.command("CREATE CLASS i_have_a_list ").close();
    session.command("CREATE PROPERTY i_have_a_list.id STRING").close();
    session.command("CREATE INDEX i_have_a_list.id ON i_have_a_list (id) UNIQUE").close();
    session.command("CREATE PROPERTY i_have_a_list.types EMBEDDEDLIST STRING").close();
    session.command("CREATE INDEX i_have_a_list.types ON i_have_a_list (types) NOTUNIQUE").close();

    session.begin();
    session.command(
            "INSERT INTO i_have_a_list CONTENT {\"id\": \"the_id\", \"types\": [\"aaa\", \"bbb\"]}")
        .close();

    var result = session.query("SELECT * FROM i_have_a_list WHERE types = 'aaa'");
    assertEquals(result.stream().count(), 1);

    session.command(
            "UPDATE i_have_a_list CONTENT {\"id\": \"the_id\", \"types\": [\"ccc\", \"bbb\"]} WHERE"
                + " id = 'the_id'")
        .close();
    session.commit();

    session.executeInTx(() -> {
      var r = session.query("SELECT * FROM i_have_a_list WHERE types = 'ccc'");
      assertEquals(r.stream().count(), 1);

      r = session.query("SELECT * FROM i_have_a_list WHERE types = 'aaa'");
      assertEquals(r.stream().count(), 0);
    });

  }

  @Test
  public void testNamedParamsSyntax() {
    // issue #4470
    var className = getClass().getSimpleName() + "_NamedParamsSyntax";

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("name", "foo");
    params.put("full_name", "foo");
    params.put("html_url", "foo");
    params.put("description", "foo");
    params.put("git_url", "foo");
    params.put("ssh_url", "foo");
    params.put("clone_url", "foo");
    params.put("svn_url", "foo");
    session.command("create class " + className).close();

    session.executeInTx(() -> {
      session.command(
              "update "
                  + className
                  + " SET name = :name, full_name = :full_name, html_url = :html_url, description ="
                  + " :description, git_url = :git_url, ssh_url = :ssh_url, clone_url = :clone_url,"
                  + " svn_url = :svn_urlUPSERT WHERE full_name = :full_name",
              params)
          .close();

      session.command(
              "update "
                  + className
                  + " SET name = :name, html_url = :html_url, description = :description, git_url ="
                  + " :git_url, ssh_url = :ssh_url, clone_url = :clone_url, svn_url = :svn_urlUPSERT"
                  + " WHERE full_name = :full_name",
              params)
          .close();
    });
  }

  @Test
  public void testUpsertSetPut() throws Exception {
    session.command("CREATE CLASS test").close();
    session.command("CREATE PROPERTY test.id integer").close();
    session.command("CREATE PROPERTY test.addField EMBEDDEDSET string").close();

    session.begin();
    session.command("UPDATE test SET id = 1 , addField=[\"xxxx\"] UPSERT WHERE id = 1").close();
    session.commit();

    try (var result = session.query("select from test")) {
      var doc = result.next();
      Set<?> set = doc.getProperty("addField");
      assertEquals(set.size(), 1);
      assertEquals(set.iterator().next(), "xxxx");
    }
  }

  @Test
  public void testUpdateParamDate() throws Exception {
    session.command("CREATE CLASS test").close();
    var date = new Date();

    session.begin();
    session.command("insert into test set birthDate = ?", date).close();
    session.commit();
    try (var result = session.query("select from test")) {
      var doc = result.next();
      assertEquals(doc.getProperty("birthDate"), date);
    }

    date = new Date();
    session.begin();
    session.command("UPDATE test set birthDate = ?", date).close();
    session.commit();

    try (var result = session.query("select from test")) {
      var doc = result.next();
      assertEquals(doc.getProperty("birthDate"), date);
    }
  }

  // issue #4776
  @Test
  public void testBooleanListNamedParameter() {
    session.getMetadata().getSchema().createClass("test");

    session.executeInTx(() -> {
      var doc = (EntityImpl) session.newEntity("test");
      doc.setProperty("id", 1);
      doc.setProperty("boolean", false);
      doc.getOrCreateEmbeddedList("integerList");
      doc.getOrCreateEmbeddedList("booleanList");
    });

    Map<String, Object> params = new HashMap<String, Object>();

    params.put("boolean", true);

    List<Object> integerList = new ArrayList<Object>();
    integerList.add(1);
    params.put("integerList", integerList);

    List<Object> booleanList = new ArrayList<Object>();
    booleanList.add(true);
    params.put("booleanList", booleanList);

    session.executeInTx(() -> {
      session.command(
              "UPDATE test SET boolean = :boolean, booleanList = :booleanList, integerList ="
                  + " :integerList WHERE id = 1",
              params)
          .close();
    });

    session.executeInTx(() -> {
      try (var queryResult = session.command("SELECT * FROM test WHERE id = 1")) {
        var docResult = queryResult.next();
        List<?> resultBooleanList = docResult.getProperty("booleanList");
        assertNotNull(resultBooleanList);
        assertEquals(resultBooleanList.size(), 1);
        assertEquals(resultBooleanList.iterator().next(), true);
        assertFalse(queryResult.hasNext());
      }
    });
  }

  @Test
  public void testIncrementWithDotNotationField() throws Exception {

    session.command("CREATE class test").close();

    session.executeInTx(() -> {
      final var test = (EntityImpl) session.newEntity("test");
      test.setProperty("id", "id1");
      test.setProperty("count", 20);

      test.getOrCreateEmbeddedMap("map").put("nestedCount", 10);
    });

    var queried =
        session.computeInTx(
            () -> session.query("SELECT FROM test WHERE id = \"id1\"").next().asEntity());

    session.executeInTx(() ->
        session.command("UPDATE test set count += 2").close()
    );

    session.executeInTx(() ->
        assertThat(session.bindToSession(queried).<Integer>getProperty("count"))
            .isEqualTo(22));

    session.executeInTx(() ->
        session.command("UPDATE test set map.nestedCount = map.nestedCount + 5").close()
    );

    session.executeInTx(() ->
        assertThat(session.bindToSession(queried).<Map>getProperty("map").get("nestedCount"))
            .isEqualTo(15));

    session.executeInTx(() ->
        session.command("UPDATE test set map.nestedCount = map.nestedCount+ 5").close()
    );

    session.executeInTx(() ->
        assertThat(session.bindToSession(queried).<Map>getProperty("map").get("nestedCount"))
            .isEqualTo(20));
  }

  @Test
  public void testSingleQuoteInNamedParameter() throws Exception {
    session.command("CREATE class test").close();

    session.executeInTx(() -> {
      final var test = (EntityImpl) session.newEntity("test");
      test.setProperty("text", "initial value");
    });

    final var queried =
        session.computeInTx(() -> {
          final var q = session.query("SELECT FROM test").next().asEntity();
          assertEquals(q.getProperty("text"), "initial value");
          return q;
        });

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("text", "single \"");

    session.executeInTx(() ->
        session.command("UPDATE test SET text = :text", params).close()
    );

    session.executeInTx(() ->
        assertEquals(session.bindToSession(queried).getProperty("text"), "single \"")
    );
  }

  @Test
  public void testQuotedStringInNamedParameter() throws Exception {

    session.command("CREATE class test").close();

    session.executeInTx(() -> {
      final var test = (EntityImpl) session.newEntity("test");
      test.setProperty("text", "initial value");
    });

    final var queried = session.computeInTx(() -> {
      var q = session.query("SELECT FROM test").next().asEntity();
      assertEquals(q.getProperty("text"), "initial value");
      return q;
    });

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("text", "quoted \"value\" string");

    session.executeInTx(() ->
        session.command("UPDATE test SET text = :text", params).close());

    session.executeInTx(() ->
        assertEquals(session.bindToSession(queried).getProperty("text"), "quoted \"value\" string")
    );
  }

  @Test
  public void testQuotesInJson() throws Exception {

    session.command("CREATE class testquotesinjson").close();

    session.begin();
    session.command(
            "UPDATE testquotesinjson SET value = {\"f12\":'test\\\\'} UPSERT WHERE key = \"test\"")
        .close();
    session.commit();

    var queried = session.query("SELECT FROM testquotesinjson").next().asEntity();
    assertEquals(((Map) queried.getProperty("value")).get("f12"), "test\\");
  }

  @Test
  public void testDottedTargetInScript() {
    // #issue #5397
    session.command("create class A").close();
    session.command("create class B").close();

    session.begin();
    session.command("insert into A set name = 'foo'").close();
    session.command("insert into B set name = 'bar', a = (select from A)").close();
    session.commit();

    var script = "let $a = select from B;\n" + "update $a.a set name = 'baz';\n";

    session.begin();
    session.command(new CommandScript(script)).execute(session);
    session.commit();

    try (var result = session.query("select from A")) {
      assertEquals(result.next().getProperty("name"), "baz");
      assertFalse(result.hasNext());
    }
  }

  @Test
  public void testBacktickClassName() throws Exception {
    session.getMetadata().getSchema().createClass("foo-bar");
    session.begin();
    session.command("insert into `foo-bar` set name = 'foo'").close();
    session.command("UPDATE `foo-bar` set name = 'bar' where name = 'foo'").close();
    session.commit();

    try (var result = session.query("select from `foo-bar`")) {
      assertEquals(result.next().getProperty("name"), "bar");
    }
  }

  @Test
  @Ignore
  public void testUpdateLockLimit() throws Exception {
    session.getMetadata().getSchema().createClass("foo");
    session.command("insert into foo set name = 'foo'").close();
    session.command("UPDATE foo set name = 'bar' where name = 'foo' lock record limit 1").close();
    try (var result = session.query("select from foo")) {
      assertEquals(result.next().getProperty("name"), "bar");
    }
    session.command("UPDATE foo set name = 'foo' where name = 'bar' lock record limit 1").close();
  }

  @Test
  public void testUpdateContentNotORestricted() throws Exception {
    // issue #5564
    session.command("CREATE class Foo").close();

    session.begin();
    var d = (EntityImpl) session.newEntity("Foo");
    d.setProperty("name", "foo");

    session.command("update Foo MERGE {\"a\":1}").close();
    session.command("update Foo CONTENT {\"a\":1}").close();
    session.commit();

    try (var result = session.query("select from Foo")) {

      var doc = result.next();
      assertNull(doc.getProperty("_allowRead"));
      assertFalse(result.hasNext());
    }
  }

  @Test
  public void testUpdateReturnCount() throws Exception {
    // issue #5564
    session.command("CREATE class Foo").close();

    session.begin();
    var d = (EntityImpl) session.newEntity("Foo");
    d.setProperty("name", "foo");

    session.commit();

    session.begin();
    d = (EntityImpl) session.newEntity("Foo");
    d.setProperty("name", "bar");

    session.commit();

    session.begin();
    var result = session.command("update Foo set surname = 'baz' return count");
    session.commit();

    assertEquals(2, (long) result.next().getProperty("count"));
  }

  @Test
  public void testLinkedUpdate() {
    session.command("CREATE class TestSource").close();
    session.command("CREATE class TestLinked").close();
    session.command("CREATE property TestLinked.id STRING").close();
    session.command("CREATE INDEX TestLinked.id ON TestLinked (id) UNIQUE")
        .close();

    session.begin();
    var state = (EntityImpl) session.newEntity("TestLinked");
    state.setProperty("id", "idvalue");
    session.commit();

    session.begin();
    var d = (EntityImpl) session.newEntity("TestSource");
    state = session.bindToSession(state);
    d.setProperty("name", "foo");
    d.setProperty("linked", state);
    session.commit();

    session.begin();
    session.command(
            "Update TestSource set flag = true , linked.flag = true return after *, linked:{*} as"
                + " infoLinked  where name = \"foo\"")
        .close();
    session.commit();

    var result = session.query("select from TestLinked where id = \"idvalue\"");
    while (result.hasNext()) {
      var res = result.next();
      assertTrue(res.hasProperty("flag"));
      assertTrue(res.getProperty("flag"));
    }
  }
}
