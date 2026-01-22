package com.jetbrains.youtrackdb.internal.core.sql.select;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.junit.Ignore;
import org.junit.Test;

public class TestManyProperties {

  @Test
  @Ignore
  public void test() {
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      youTrackDB.create("test", DatabaseType.MEMORY,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
      try (var session = youTrackDB.open("test", "admin", "admin")) {
        var clazz = session.getSchema().createClass("test");
        clazz.createProperty("property1", PropertyType.STRING);
        clazz.createProperty("property2", PropertyType.STRING);
        clazz.createProperty("property3", PropertyType.STRING);
        clazz.createProperty("property4", PropertyType.STRING);
        clazz.createProperty("property5", PropertyType.STRING);
        clazz.createProperty("property6", PropertyType.STRING);
        clazz.createProperty("property7", PropertyType.STRING);
        clazz.createProperty("property8", PropertyType.STRING);
        clazz.createProperty("property9", PropertyType.STRING);
        clazz.createProperty("property10", PropertyType.STRING);
        clazz.createProperty("property11", PropertyType.STRING);
        clazz.createProperty("property12", PropertyType.STRING);
        clazz.createProperty("property13", PropertyType.STRING);
        clazz.createProperty("property14", PropertyType.STRING);
        clazz.createProperty("property15", PropertyType.STRING);
        clazz.createProperty("property16", PropertyType.STRING);
        clazz.createProperty("property17", PropertyType.STRING);
        clazz.createProperty("property18", PropertyType.STRING);
        clazz.createProperty("property19", PropertyType.STRING);
        clazz.createProperty("property20", PropertyType.STRING);
        clazz.createProperty("property21", PropertyType.STRING);
        clazz.createProperty("property22", PropertyType.STRING);
        clazz.createProperty("property23", PropertyType.STRING);
        clazz.createProperty("property24", PropertyType.STRING);

        session.executeInTx(transaction -> {
          try (var set =
              transaction.query(
                  "SELECT FROM test WHERE (((property1 is null) or (property1 = #107:150)) and"
                      + " ((property2 is null) or (property2 = #107:150)) and ((property3 is null) or"
                      + " (property3 = #107:150)) and ((property4 is null) or (property4 = #107:150))"
                      + " and ((property5 is null) or (property5 = #107:150)) and ((property6 is"
                      + " null) or (property6 = #107:150)) and ((property7 is null) or (property7 ="
                      + " #107:150)) and ((property8 is null) or (property8 = #107:150)) and"
                      + " ((property9 is null) or (property9 = #107:150)) and ((property10 is null)"
                      + " or (property10 = #107:150)) and ((property11 is null) or (property11 ="
                      + " #107:150)) and ((property12 is null) or (property12 = #107:150)) and"
                      + " ((property13 is null) or (property13 = #107:150)) and ((property14 is null)"
                      + " or (property14 = #107:150)) and ((property15 is null) or (property15 ="
                      + " #107:150)) and ((property16 is null) or (property16 = #107:150)) and"
                      + " ((property17 is null) or (property17 = #107:150)))")) {
            assertEquals(set.stream().count(), 0);
          }
        });
      }
    }
  }
}
