package com.jetbrains.youtrackdb.auto.hooks;

import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

public class HookOnIndexedMapTest {

  @Test
  public void test() {
    var youTrackDb = YourTracks.embedded(
        DbTestBase.getBaseDirectoryPath(HookOnIndexedMapTest.class));
    youTrackDb.execute(
        "create database " + "test" + " memory users ( admin identified by 'admin' role admin)");
    var db = youTrackDb.open("test", "admin", "admin");
    db.registerHook(new BrokenMapHook());

    db.computeScript("sql", "CREATE CLASS AbsVertex IF NOT EXISTS EXTENDS V ABSTRACT;");
    db.computeScript("sql", "CREATE PROPERTY AbsVertex.uId IF NOT EXISTS string;");
    db.computeScript("sql", "CREATE PROPERTY AbsVertex.myMap IF NOT EXISTS EMBEDDEDMAP;");

    db.computeScript("sql", "CREATE CLASS MyClass IF NOT EXISTS EXTENDS AbsVertex;");
    db.computeScript("sql", "CREATE INDEX MyClass.uId IF NOT EXISTS ON MyClass(uId) UNIQUE;");
    db.computeScript("sql",
        "CREATE INDEX MyClass.myMap IF NOT EXISTS ON MyClass(myMap by key) NOTUNIQUE;");

    var tx = db.begin();
    tx.command("INSERT INTO MyClass SET uId = \"test1\", myMap={\"F1\": \"V1\"}");
    tx.commit();

    tx = db.begin();

    try (var rs = tx.execute("SELECT FROM V")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT FROM V");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    tx.command("UPDATE MyClass SET myMap = {\"F11\": \"V11\"} WHERE uId = \"test1\"");

    try (var rs = tx.execute("SELECT FROM V")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT FROM V");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    try (var rs = tx.execute("SELECT COUNT(*) FROM MyClass WHERE myMap.F1 IS NOT NULL")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT COUNT(*) FROM MyClass WHERE myMap.F1 IS NOT NULL");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    try (var rs = tx.query("SELECT COUNT(*) FROM MyClass WHERE myMap CONTAINSKEY 'F1'")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT COUNT(*) FROM MyClass WHERE myMap CONTAINSKEY 'F1'");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    tx.command("DELETE VERTEX FROM V");
    tx.commit();

    youTrackDb.close();
  }
}
