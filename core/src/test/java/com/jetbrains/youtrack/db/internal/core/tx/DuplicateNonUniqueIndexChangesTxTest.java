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

package com.jetbrains.youtrack.db.internal.core.tx;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class DuplicateNonUniqueIndexChangesTxTest extends DbTestBase {

  private Index index;

  public void beforeTest() throws Exception {
    super.beforeTest();
    final var class_ = session.getMetadata().getSchema().createClass("Person");
    var indexName =
        class_
            .createProperty("name", PropertyType.STRING)
            .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    index = session.getIndex(indexName);
  }

  @Test
  public void testDuplicateNullsOnCreate() {
    session.begin();

    // saved persons will have null name
    final var person1 = session.newInstance("Person");
    final var person2 = session.newInstance("Person");
    final var person3 = session.newInstance("Person");

    // change some names
    person3.setProperty("name", "Name3");

    session.commit();

    // verify index state
    session.begin();
    var activeTx1 = session.getActiveTransaction();
    var activeTx2 = session.getActiveTransaction();
    assertRids(null, activeTx2.<EntityImpl>load(person1), activeTx1.<EntityImpl>load(person2));
    var activeTx = session.getActiveTransaction();
    assertRids("Name3", activeTx.<EntityImpl>load(person3));
    session.commit();
  }

  @Test
  public void testDuplicateNullsOnUpdate() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    person2.setProperty("name", "Name2");
    var person3 = session.newInstance("Person");
    person3.setProperty("name", "Name3");
    session.commit();

    session.begin();
    // verify index state
    assertRids(null);
    var activeTx8 = session.getActiveTransaction();
    assertRids("Name1", activeTx8.<EntityImpl>load(person1));
    var activeTx7 = session.getActiveTransaction();
    assertRids("Name2", activeTx7.<EntityImpl>load(person2));
    var activeTx6 = session.getActiveTransaction();
    assertRids("Name3", activeTx6.<EntityImpl>load(person3));
    session.commit();

    session.begin();

    var activeTx5 = session.getActiveTransaction();
    person1 = activeTx5.load(person1);
    var activeTx4 = session.getActiveTransaction();
    person2 = activeTx4.load(person2);
    var activeTx3 = session.getActiveTransaction();
    person3 = activeTx3.load(person3);

    // saved persons will have null name
    person1.setProperty("name", null);

    person2.setProperty("name", null);

    person3.setProperty("name", null);

    // change names
    person1.setProperty("name", "Name2");

    person2.setProperty("name", "Name1");

    person3.setProperty("name", "Name2");

    // and again
    person1.setProperty("name", "Name1");

    person2.setProperty("name", "Name2");

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    person1 = activeTx2.load(person1);
    var activeTx1 = session.getActiveTransaction();
    person2 = activeTx1.load(person2);
    var activeTx = session.getActiveTransaction();
    person3 = activeTx.load(person3);

    // verify index state
    assertRids(null);
    assertRids("Name1", person1);
    assertRids("Name2", person2, person3);
    assertRids("Name3");
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnCreate() {
    session.begin();

    // saved persons will have same name
    final var person1 = session.newInstance("Person");
    person1.setProperty("name", "same");
    final var person2 = session.newInstance("Person");
    person2.setProperty("name", "same");
    final var person3 = session.newInstance("Person");
    person3.setProperty("name", "same");

    // change some names
    person2.setProperty("name", "Name1");

    person2.setProperty("name", "Name2");

    person3.setProperty("name", "Name2");

    session.commit();

    session.begin();
    // verify index state
    var activeTx2 = session.getActiveTransaction();
    assertRids("same", activeTx2.<EntityImpl>load(person1));
    assertRids("Name1");
    var activeTx = session.getActiveTransaction();
    var activeTx1 = session.getActiveTransaction();
    assertRids("Name2", activeTx1.<EntityImpl>load(person2), activeTx.<EntityImpl>load(person3));
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnUpdate() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    person2.setProperty("name", "Name2");
    var person3 = session.newInstance("Person");
    person3.setProperty("name", "Name3");
    session.commit();

    session.begin();
    var activeTx5 = session.getActiveTransaction();
    person1 = activeTx5.load(person1);
    var activeTx4 = session.getActiveTransaction();
    person2 = activeTx4.load(person2);
    var activeTx3 = session.getActiveTransaction();
    person3 = activeTx3.load(person3);


    // verify index state
    assertRids(null);
    assertRids("Name1", person1);
    assertRids("Name2", person2);
    assertRids("Name3", person3);

    // saved persons will have same name
    person1.setProperty("name", "same");

    person2.setProperty("name", "same");

    person3.setProperty("name", "same");

    // change names back to unique in reverse order
    person3.setProperty("name", "Name3");

    person2.setProperty("name", "Name2");

    person1.setProperty("name", "Name1");

    session.commit();

    session.begin();
    var activeTx2 = session.getActiveTransaction();
    person1 = activeTx2.load(person1);
    var activeTx1 = session.getActiveTransaction();
    person2 = activeTx1.load(person2);
    var activeTx = session.getActiveTransaction();
    person3 = activeTx.load(person3);

    // verify index state
    assertRids("same");
    assertRids("Name1", person1);
    assertRids("Name2", person2);
    assertRids("Name3", person3);
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnCreateDelete() {
    session.begin();

    // saved persons will have same name
    final var person1 = session.newInstance("Person");
    person1.setProperty("name", "same");
    final var person2 = session.newInstance("Person");
    person2.setProperty("name", "same");
    final var person3 = session.newInstance("Person");
    person3.setProperty("name", "same");
    final var person4 = session.newInstance("Person");
    person4.setProperty("name", "same");

    person1.delete();
    person2.setProperty("name", "Name2");

    person3.delete();
    person4.setProperty("name", "Name2");

    session.commit();

    session.begin();
    // verify index state
    assertRids("Name1");
    var activeTx = session.getActiveTransaction();
    var activeTx1 = session.getActiveTransaction();
    assertRids("Name2", activeTx1.<EntityImpl>load(person2), activeTx.<EntityImpl>load(person4));
    assertRids("Name3");
    assertRids("Name4");
    session.commit();
  }

  @Test
  public void testDuplicateValuesOnUpdateDelete() {
    session.begin();
    var person1 = session.newInstance("Person");
    person1.setProperty("name", "Name1");
    var person2 = session.newInstance("Person");
    person2.setProperty("name", "Name2");
    var person3 = session.newInstance("Person");
    person3.setProperty("name", "Name3");

    var person4 = session.newInstance("Person");
    person4.setProperty("name", "Name4");
    session.commit();

    session.begin();

    var activeTx7 = session.getActiveTransaction();
    person1 = activeTx7.load(person1);
    var activeTx6 = session.getActiveTransaction();
    person2 = activeTx6.load(person2);
    var activeTx5 = session.getActiveTransaction();
    person3 = activeTx5.load(person3);
    var activeTx4 = session.getActiveTransaction();
    person4 = activeTx4.load(person4);

    // verify index state
    assertRids("Name1", person1);
    assertRids("Name2", person2);
    assertRids("Name3", person3);
    assertRids("Name4", person4);

    person1.delete();
    person2.setProperty("name", "same");

    person3.delete();
    person4.setProperty("name", "same");

    person2.setProperty("name", "Name2");

    person4.setProperty("name", "Name2");

    session.commit();

    session.begin();
    var activeTx3 = session.getActiveTransaction();
    person2 = activeTx3.load(person2);
    var activeTx2 = session.getActiveTransaction();
    person4 = activeTx2.load(person4);

    // verify index state
    assertRids("same");
    assertRids("Name1");
    assertRids("Name2", person2, person4);
    assertRids("Name3");
    assertRids("Name4");
    session.commit();

    session.begin();
    var activeTx1 = session.getActiveTransaction();
    person2 = activeTx1.load(person2);
    var activeTx = session.getActiveTransaction();
    person4 = activeTx.load(person4);

    person2.delete();
    person4.delete();
    session.commit();

    // verify index state
    assertRids("Name2");
  }

  @Test
  public void testManyManyUpdatesToTheSameKey() {
    final Set<Integer> unseen = new HashSet<Integer>();

    session.begin();
    for (var i = 0; i < FrontendTransactionIndexChangesPerKey.SET_ADD_THRESHOLD << 1; ++i) {
      var pers = session.newInstance("Person");
      pers.setProperty("name", "Name");
      pers.setProperty("serial", i);
      unseen.add(i);
    }
    session.commit();

    session.begin();
    // verify index state
    try (var stream = index.getRids(session, "Name")) {
      stream.forEach(
          (rid) -> {
            final EntityImpl document = session.load(rid);
            unseen.remove(document.<Integer>getProperty("serial"));
          });
    }
    Assert.assertTrue(unseen.isEmpty());
    session.commit();
  }

  private void assertRids(String indexKey, Identifiable... rids) {
    final Set<RID> actualRids;
    try (var stream = index.getRids(session, indexKey)) {
      actualRids = stream.collect(Collectors.toSet());
    }
    Assert.assertEquals(actualRids, new HashSet<Object>(Arrays.asList(rids)));
  }
}
