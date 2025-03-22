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
 *
 */

package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Assert;
import org.junit.Test;

public class DocumentTest extends DbTestBase {

  @Test
  public void testFromMapNotSaved() {
    session.begin();
    final var doc = session.newEntity();
    doc.setString("name", "Jay");
    doc.setString("surname", "Miner");
    var map = doc.toMap(false);

    Assert.assertEquals(2, map.size());
    Assert.assertEquals("Jay", map.get("name"));
    Assert.assertEquals("Miner", map.get("surname"));
    session.rollback();
  }

  @Test
  public void testFromMapWithClass() {
    session.begin();
    final var doc = (EntityImpl) session.newEntity("OUser");
    doc.setProperty("name", "Jay");
    doc.setProperty("surname", "Miner");
    var map = doc.toMap();

    Assert.assertEquals(4, map.size());
    Assert.assertEquals("Jay", map.get("name"));
    Assert.assertEquals("Miner", map.get("surname"));
    Assert.assertEquals("OUser", map.get("@class"));
    Assert.assertTrue(map.containsKey("@rid"));
    session.rollback();
  }

  @Test
  public void testFromMapWithClassAndRid() {
    session.begin();
    final var doc = (EntityImpl) session.newVertex();
    doc.setProperty("name", "Jay");
    doc.setProperty("surname", "Miner");

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    var map = activeTx.<EntityImpl>load(doc).toMap();

    Assert.assertEquals(4, map.size());
    Assert.assertEquals("Jay", map.get("name"));
    Assert.assertEquals("Miner", map.get("surname"));
    Assert.assertEquals("V", map.get("@class"));
    Assert.assertTrue(map.containsKey("@rid"));
    session.commit();
  }

  @Test
  public void testConversionOnTypeSet() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();

    doc.setProperty("some", 3, PropertyType.STRING);
    Assert.assertEquals(PropertyType.STRING, doc.getPropertyType("some"));
    Assert.assertEquals("3", doc.getProperty("some"));
    session.rollback();
  }

}
