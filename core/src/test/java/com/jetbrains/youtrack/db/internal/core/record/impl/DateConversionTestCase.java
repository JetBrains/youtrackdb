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

package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import org.junit.Assert;
import org.junit.Test;

public class DateConversionTestCase extends DbTestBase {

  private final RecordSerializer serializer = new RecordSerializerBinary();

  @Test
  public void testDateSerializationWithDST() throws ParseException {

    // write on the db a vertex with a date:
    // 1975-05-31 23:00:00 GMT OR 1975-06-01 01:00:00 (GMT+1) +DST (+2 total)
    var format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    var dateToInsert = format.parse("1975-06-01 01:00:00");

    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.setProperty("date", dateToInsert, PropertyType.DATE);
    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    final var fields = extr.propertyNames();

    assertNotNull(fields);
    assertEquals(1, fields.length);
    assertEquals("date", fields[0]);

    Date old = document.getProperty("date");
    Date newDate = extr.getProperty("date");
    var cal = Calendar.getInstance();
    cal.setTime(old);
    var cal1 = Calendar.getInstance();
    cal1.setTime(old);
    assertEquals(cal.get(Calendar.YEAR), cal1.get(Calendar.YEAR));
    assertEquals(cal.get(Calendar.MONTH), cal1.get(Calendar.MONTH));
    assertEquals(cal.get(Calendar.DAY_OF_MONTH), cal1.get(Calendar.DAY_OF_MONTH));
    session.rollback();
  }

  @Test
  public void testDateFormantWithMethod() throws ParseException {
    try (YouTrackDB ctx = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var session = (DatabaseSessionInternal) ctx.open("test", "admin", "adminpwd")) {

        var format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        var date = format.parse("2016-08-31 23:30:00");

        session.set(DatabaseSession.ATTRIBUTES.TIMEZONE, "GMT");

        session.begin();
        var doc = (EntityImpl) session.newEntity();

        doc.setProperty("dateTime", date);
        var formatted = doc.eval("dateTime.format('yyyy-MM-dd')").toString();

        Assert.assertEquals("2016-08-31", formatted);
        session.rollback();
      }
      ctx.drop("test");
    }
  }
}
