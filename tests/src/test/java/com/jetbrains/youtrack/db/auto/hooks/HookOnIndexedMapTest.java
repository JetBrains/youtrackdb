package com.jetbrains.youtrack.db.auto.hooks;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import org.junit.Assert;
import org.junit.Test;

public class HookOnIndexedMapTest {

  @Test
  public void test() {
    YouTrackDB youTrackDb = new YouTrackDBImpl("plocal:.", "root", "root",
        YouTrackDBConfig.defaultConfig());

    youTrackDb.execute(
        "create database " + "test" + " memory users ( admin identified by 'admin' role admin)");
    var db = youTrackDb.open("test", "admin", "admin");
    db.registerHook(new BrokenMapHook());

    db.execute("CREATE CLASS AbsVertex IF NOT EXISTS EXTENDS V ABSTRACT;");
    db.execute("CREATE PROPERTY AbsVertex.uId IF NOT EXISTS string;");
    db.execute("CREATE PROPERTY AbsVertex.myMap IF NOT EXISTS EMBEDDEDMAP;");

    db.execute("CREATE CLASS MyClass IF NOT EXISTS EXTENDS AbsVertex;");
    db.execute("CREATE INDEX MyClass.uId IF NOT EXISTS ON MyClass(uId) UNIQUE;");
    db.execute("CREATE INDEX MyClass.myMap IF NOT EXISTS ON MyClass(myMap by key) NOTUNIQUE;");

    db.execute("INSERT INTO MyClass SET uId = \"test1\", myMap={\"F1\": \"V1\"}");

    try (var rs = db.execute("SELECT * FROM INDEX:MyClass.myMap ORDER BY rid")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT * FROM INDEX:MyClass.myMap ORDER BY rid");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    try (var rs = db.execute("SELECT FROM V")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT FROM V");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    db.execute("UPDATE MyClass SET myMap = {\"F11\": \"V11\"} WHERE uId = \"test1\"");

    try (var rs = db.execute("SELECT FROM V")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT FROM V");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    try (var rs = db.execute("SELECT * FROM INDEX:MyClass.myMap ORDER BY rid")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT * FROM INDEX:MyClass.myMap ORDER BY rid");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    try (var rs = db.execute("SELECT COUNT(*) FROM MyClass WHERE myMap.F1 IS NOT NULL")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT COUNT(*) FROM MyClass WHERE myMap.F1 IS NOT NULL");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    try (var rs = db.query("SELECT COUNT(*) FROM MyClass WHERE myMap CONTAINSKEY 'F1'")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT COUNT(*) FROM MyClass WHERE myMap CONTAINSKEY 'F1'");
      //      rs.forEachRemaining(x-> System.out.println(x));
    }

    db.execute("DELETE VERTEX FROM V");

    try (var rs = db.execute("SELECT * FROM INDEX:MyClass.myMap ORDER BY rid")) {
      //      System.out.println("----------");
      //      System.out.println("SELECT * FROM INDEX:MyClass.myMap ORDER BY rid");
      if (rs.hasNext()) {
        //        System.out.println(rs.next());
        Assert.fail();
      }
    }
    youTrackDb.close();
  }
}
