/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionIndexKeySize} — returns the distinct key count across a named
 * index.
 *
 * <p>Uses {@link DbTestBase} because the function needs a live
 * {@code SharedContext().getIndexManager()}.
 *
 * <p>Covered behaviour:
 *
 * <ul>
 *   <li>Happy path: non-unique index with two distinct keys → 2.
 *   <li>Unique index with three distinct keys → 3.
 *   <li>Non-unique index with duplicate keys → distinct-key count (not total row count).
 *   <li>Unknown index name → {@code null}.
 *   <li>{@code null} input → {@code "null"} index name lookup → {@code null}.
 *   <li>Non-String input is coerced via {@code String.valueOf}.
 *   <li>Metadata: name, min/max params, syntax.
 * </ul>
 */
public class SQLFunctionIndexKeySizeTest extends DbTestBase {

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  // ---------------------------------------------------------------------------
  // SQL-query happy path (existing test — retained for behavioural pin)
  // ---------------------------------------------------------------------------

  @Test
  public void nonUniqueIndexReturnsDistinctKeyCountViaSql() {
    var clazz = session.getMetadata().getSchema().createClass("Test");
    clazz.createProperty("name", PropertyType.STRING);
    session.execute("create index testindex on  Test (name) notunique").close();

    session.begin();
    session.execute("insert into Test set name = 'a'").close();
    session.execute("insert into Test set name = 'b'").close();
    session.commit();

    try (var rs = session.query("select indexKeySize('testindex') as foo")) {
      Assert.assertTrue(rs.hasNext());
      var item = rs.next();
      assertEquals((Object) 2L, item.getProperty("foo"));
      Assert.assertFalse(rs.hasNext());
    }
  }

  // ---------------------------------------------------------------------------
  // Direct-call paths
  // ---------------------------------------------------------------------------

  @Test
  public void uniqueIndexDirectCallReturnsDistinctKeyCount() {
    // Bypass the SQL parser and call the function directly — exercises the happy-path branch
    // without going through the parser stack. index.stream(session) requires an active tx.
    var clazz = session.getMetadata().getSchema().createClass("Unique");
    clazz.createProperty("code", PropertyType.STRING);
    session.execute("create index uniqueindex on Unique (code) unique").close();

    session.begin();
    session.execute("insert into Unique set code = 'one'").close();
    session.execute("insert into Unique set code = 'two'").close();
    session.execute("insert into Unique set code = 'three'").close();
    session.commit();

    session.begin();
    try {
      var function = new SQLFunctionIndexKeySize();

      var result = function.execute(null, null, null, new Object[] {"uniqueindex"}, ctx());

      assertEquals("unique index with 3 rows must report 3 distinct keys", 3L, result);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void nonUniqueIndexWithDuplicateKeysReturnsDistinctCount() {
    // Duplicate-key insertions against a non-unique index — distinct-key count must be 2, not
    // the total row count of 5.
    var clazz = session.getMetadata().getSchema().createClass("Tag");
    clazz.createProperty("label", PropertyType.STRING);
    session.execute("create index tagindex on Tag (label) notunique").close();

    session.begin();
    session.execute("insert into Tag set label = 'x'").close();
    session.execute("insert into Tag set label = 'x'").close();
    session.execute("insert into Tag set label = 'y'").close();
    session.execute("insert into Tag set label = 'y'").close();
    session.execute("insert into Tag set label = 'y'").close();
    session.commit();

    session.begin();
    try {
      var function = new SQLFunctionIndexKeySize();

      var result = function.execute(null, null, null, new Object[] {"tagindex"}, ctx());

      assertEquals("duplicate keys must not inflate the distinct count", 2L, result);
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Unknown / null / non-String input — null return
  // ---------------------------------------------------------------------------

  @Test
  public void emptyIndexReturnsZero() {
    // Boundary: stream.count() + rids.count() == 0 for an index with no keys. Pin the zero-key
    // contract so a refactor that returns null or throws on empty would be noticed.
    var clazz = session.getMetadata().getSchema().createClass("Empty");
    clazz.createProperty("k", PropertyType.STRING);
    session.execute("create index emptyindex on Empty (k) notunique").close();

    session.begin();
    try {
      var function = new SQLFunctionIndexKeySize();

      var result = function.execute(null, null, null, new Object[] {"emptyindex"}, ctx());

      assertEquals("empty index should report 0 distinct keys", 0L, result);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void emptyStringIndexNameReturnsNull() {
    // Boundary: the empty-string name has no matching index → `index == null` → null return.
    var function = new SQLFunctionIndexKeySize();

    var result = function.execute(null, null, null, new Object[] {""}, ctx());

    assertNull(result);
  }

  @Test
  public void unknownIndexNameReturnsNull() {
    // Production: `if (index == null) return null;` — no index.stream() is invoked, so no tx
    // is required.
    var function = new SQLFunctionIndexKeySize();

    var result = function.execute(null, null, null, new Object[] {"no-such-index"}, ctx());

    assertNull(result);
  }

  @Test
  public void nullInputIsCoercedToLiteralNullStringAndReturnsNull() {
    // String.valueOf(null) == "null" — no index with that literal name → null. Same "null index"
    // path as the unknown-name test, so no tx is required.
    var function = new SQLFunctionIndexKeySize();

    var result = function.execute(null, null, null, new Object[] {(Object) null}, ctx());

    assertNull(result);
  }

  @Test
  public void nonStringInputIsCoercedViaStringValueOf() {
    // A StringBuilder whose toString is "sbindex" must resolve the index via String.valueOf.
    var clazz = session.getMetadata().getSchema().createClass("SB");
    clazz.createProperty("name", PropertyType.STRING);
    session.execute("create index sbindex on SB (name) notunique").close();

    session.begin();
    session.execute("insert into SB set name = 'z'").close();
    session.commit();

    session.begin();
    try {
      var function = new SQLFunctionIndexKeySize();

      var result = function.execute(null, null, null, new Object[] {new StringBuilder("sbindex")},
          ctx());

      assertEquals(1L, result);
    } finally {
      session.rollback();
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------

  @Test
  public void nameMinMaxAndSyntaxMatchFunctionContract() {
    var function = new SQLFunctionIndexKeySize();

    assertEquals("indexKeySize", SQLFunctionIndexKeySize.NAME);
    assertEquals(SQLFunctionIndexKeySize.NAME, function.getName(session));
    assertEquals(1, function.getMinParams());
    assertEquals(1, function.getMaxParams(session));
    assertEquals("indexKeySize(<indexName-string>)", function.getSyntax(session));
  }
}
