/*
 *
 *
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
 */

package com.jetbrains.youtrack.db.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class TxNonUniqueIndexWithCollationTest extends DbTestBase {

  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    session.getMetadata()
        .getSchema()
        .createClass("user")
        .createProperty("name", PropertyType.STRING)
        .setCollate("ci")
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);

    session.begin();
    var user = session.newEntity("user");
    user.setProperty("name", "abc");
    user = session.newEntity("user");
    user.setProperty("name", "aby");
    user = session.newEntity("user");
    user.setProperty("name", "aby");
    user = session.newEntity("user");
    user.setProperty("name", "abz");
    session.commit();
  }

  @Test
  public void testSubstrings() {
    session.begin();

    session.execute("update user set name='abd' where name='Aby'").close();

    final var r = session.execute("select * from user where name like '%B%' order by name");
    assertEquals("abc", r.next().getProperty("name"));
    assertEquals("abd", r.next().getProperty("name"));
    assertEquals("abd", r.next().getProperty("name"));
    assertEquals("abz", r.next().getProperty("name"));
    assertFalse(r.hasNext());
    r.close();

    session.commit();
  }

  @Test
  public void testRange() {
    session.begin();

    session.execute("update user set name='Abd' where name='Aby'").close();

    final var r = session.query("select * from user where name >= 'abd' order by name");
    assertEquals("Abd", r.next().getProperty("name"));
    assertEquals("Abd", r.next().getProperty("name"));
    assertEquals("abz", r.next().getProperty("name"));
    assertFalse(r.hasNext());
    r.close();

    session.commit();
  }

  @Test
  public void testIn() {
    session.begin();

    session.execute("update user set name='abd' where name='Aby'").close();

    final var r =
        session.query("select * from user where name in ['Abc', 'Abd', 'Abz'] order by name")
            .stream()
            .map(x -> ((EntityImpl) (x.asEntityOrNull())))
            .toList();
    assertEquals(4, r.size());
    assertEquals("abc", r.get(0).getProperty("name"));
    assertEquals("abd", r.get(1).getProperty("name"));
    assertEquals("abd", r.get(2).getProperty("name"));
    assertEquals("abz", r.get(3).getProperty("name"));

    session.commit();
  }
}
