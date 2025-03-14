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
    final EntityImpl person1 = session.newInstance("Person");
    final EntityImpl person2 = session.newInstance("Person");
    final EntityImpl person3 = session.newInstance("Person");

    // change some names
    person3.field("name", "Name3");

    session.commit();

    // verify index state
    assertRids(null, person1, person2);
    assertRids("Name3", person3);
  }

  @Test
  public void testDuplicateNullsOnUpdate() {
    session.begin();
    EntityImpl person1 = session.newInstance("Person");
    person1.field("name", "Name1");
    EntityImpl person2 = session.newInstance("Person");
    person2.field("name", "Name2");
    EntityImpl person3 = session.newInstance("Person");
    person3.field("name", "Name3");
    session.commit();

    // verify index state
    assertRids(null);
    assertRids("Name1", person1);
    assertRids("Name2", person2);
    assertRids("Name3", person3);

    session.begin();

    person1 = session.bindToSession(person1);
    person2 = session.bindToSession(person2);
    person3 = session.bindToSession(person3);

    // saved persons will have null name
    person1.field("name", null);

    person2.field("name", null);

    person3.field("name", null);

    // change names
    person1.field("name", "Name2");

    person2.field("name", "Name1");

    person3.field("name", "Name2");

    // and again
    person1.field("name", "Name1");

    person2.field("name", "Name2");

    session.commit();

    session.begin();
    person1 = session.bindToSession(person1);
    person2 = session.bindToSession(person2);
    person3 = session.bindToSession(person3);

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
    final EntityImpl person1 = session.newInstance("Person");
    person1.field("name", "same");
    final EntityImpl person2 = session.newInstance("Person");
    person2.field("name", "same");
    final EntityImpl person3 = session.newInstance("Person");
    person3.field("name", "same");

    // change some names
    person2.field("name", "Name1");

    person2.field("name", "Name2");

    person3.field("name", "Name2");

    session.commit();

    // verify index state
    assertRids("same", person1);
    assertRids("Name1");
    assertRids("Name2", person2, person3);
  }

  @Test
  public void testDuplicateValuesOnUpdate() {
    session.begin();
    EntityImpl person1 = session.newInstance("Person");
    person1.field("name", "Name1");
    EntityImpl person2 = session.newInstance("Person");
    person2.field("name", "Name2");
    EntityImpl person3 = session.newInstance("Person");
    person3.field("name", "Name3");
    session.commit();

    // verify index state
    assertRids(null);
    assertRids("Name1", person1);
    assertRids("Name2", person2);
    assertRids("Name3", person3);

    session.begin();

    person1 = session.bindToSession(person1);
    person2 = session.bindToSession(person2);
    person3 = session.bindToSession(person3);

    // saved persons will have same name
    person1.field("name", "same");

    person2.field("name", "same");

    person3.field("name", "same");

    // change names back to unique in reverse order
    person3.field("name", "Name3");

    person2.field("name", "Name2");

    person1.field("name", "Name1");

    session.commit();

    session.begin();
    person1 = session.bindToSession(person1);
    person2 = session.bindToSession(person2);
    person3 = session.bindToSession(person3);

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
    final EntityImpl person1 = session.newInstance("Person");
    person1.field("name", "same");
    final EntityImpl person2 = session.newInstance("Person");
    person2.field("name", "same");
    final EntityImpl person3 = session.newInstance("Person");
    person3.field("name", "same");
    final EntityImpl person4 = session.newInstance("Person");
    person4.field("name", "same");

    person1.delete();
    person2.field("name", "Name2");

    person3.delete();
    person4.field("name", "Name2");

    session.commit();

    // verify index state
    assertRids("Name1");
    assertRids("Name2", person2, person4);
    assertRids("Name3");
    assertRids("Name4");
  }

  @Test
  public void testDuplicateValuesOnUpdateDelete() {
    session.begin();
    EntityImpl person1 = session.newInstance("Person");
    person1.field("name", "Name1");
    EntityImpl person2 = session.newInstance("Person");
    person2.field("name", "Name2");
    EntityImpl person3 = session.newInstance("Person");
    person3.field("name", "Name3");

    EntityImpl person4 = session.newInstance("Person");
    person4.field("name", "Name4");
    session.commit();

    // verify index state
    assertRids("Name1", person1);
    assertRids("Name2", person2);
    assertRids("Name3", person3);
    assertRids("Name4", person4);

    session.begin();

    person1 = session.bindToSession(person1);
    person2 = session.bindToSession(person2);
    person3 = session.bindToSession(person3);
    person4 = session.bindToSession(person4);

    person1.delete();
    person2.field("name", "same");

    person3.delete();
    person4.field("name", "same");

    person2.field("name", "Name2");

    person4.field("name", "Name2");

    session.commit();

    session.begin();
    person2 = session.bindToSession(person2);
    person4 = session.bindToSession(person4);

    // verify index state
    assertRids("same");
    assertRids("Name1");
    assertRids("Name2", person2, person4);
    assertRids("Name3");
    assertRids("Name4");
    session.commit();

    session.begin();
    person2 = session.bindToSession(person2);
    person4 = session.bindToSession(person4);

    person2.delete();
    person4.delete();
    session.commit();

    // verify index state
    assertRids("Name2");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testManyManyUpdatesToTheSameKey() {
    final Set<Integer> unseen = new HashSet<Integer>();

    session.begin();
    for (var i = 0; i < FrontendTransactionIndexChangesPerKey.SET_ADD_THRESHOLD * 2; ++i) {
      EntityImpl pers = session.newInstance("Person");
      pers.field("name", "Name");
      pers.field("serial", i);
      unseen.add(i);
    }
    session.commit();

    session.begin();
    // verify index state
    try (var stream = index.getRids(session, "Name")) {
      stream.forEach(
          (rid) -> {
            final EntityImpl document = session.load(rid);
            unseen.remove(document.<Integer>field("serial"));
          });
    }
    Assert.assertTrue(unseen.isEmpty());
    session.commit();
  }

  @SuppressWarnings("unchecked")
  private void assertRids(String indexKey, Identifiable... rids) {
    final Set<RID> actualRids;
    try (var stream = index.getRids(session, indexKey)) {
      actualRids = stream.collect(Collectors.toSet());
    }
    Assert.assertEquals(actualRids, new HashSet<Object>(Arrays.asList(rids)));
  }
}
