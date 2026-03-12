/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.util.DateHelper;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class DateTest extends BaseDBJUnit5Test {

  // Migrated from: com.jetbrains.youtrackdb.auto.DateTest#testDateConversion
  @Test
  @Order(1)
  void testDateConversion() {
    final var begin = System.currentTimeMillis();

    session.createClass("Order");
    session.begin();
    var doc1 = ((EntityImpl) session.newEntity("Order"));
    doc1.setProperty("context", "test");
    doc1.setProperty("date", new Date());

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    doc1 = activeTx.load(doc1);
    assertNotNull(doc1.getDate("date"));

    var result =
        session.execute("select * from Order where date >= ? and context = 'test'", begin);

    assertEquals(1, result.stream().count());
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.DateTest#testDatePrecision
  @Test
  @Order(2)
  void testDatePrecision() {
    final var begin = System.currentTimeMillis();

    var dateAsString =
        DateHelper.getDateFormatInstance(session).format(begin);

    session.begin();
    var doc = ((EntityImpl) session.newEntity("Order"));
    doc.setProperty("context", "testPrecision");
    Object propertyValue = DateHelper.now();
    doc.setProperty("date", propertyValue, PropertyType.DATETIME);

    session.commit();

    session.begin();
    var result =
        session
            .execute(
                "select * from Order where date >= ? and context = 'testPrecision'",
                dateAsString)
            .stream()
            .toList();

    assertEquals(1, result.size());
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.DateTest#testDateTypes
  @Test
  @Order(3)
  void testDateTypes() {
    session.begin();
    var doc = ((EntityImpl) session.newEntity());
    doc.setProperty("context", "test");
    Object propertyValue = System.currentTimeMillis();
    doc.setProperty("date", propertyValue, PropertyType.DATE);

    assertTrue(doc.getProperty("date") instanceof Date);
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.DateTest#testDateGregorianCalendar
  @Test
  @Order(4)
  void testDateGregorianCalendar() throws ParseException {
    session.execute("CREATE CLASS TimeTest EXTENDS V").close();

    final var df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    final var date = df.parse("1200-11-11 00:00:00.000");

    session.begin();
    session
        .execute("CREATE VERTEX TimeTest SET firstname = ?, birthDate = ?", "Robert", date)
        .close();
    session.commit();

    var result = session.query("select from TimeTest where firstname = ?", "Robert");
    assertEquals(date, result.next().getProperty("birthDate"));
    assertFalse(result.hasNext());
  }
}
