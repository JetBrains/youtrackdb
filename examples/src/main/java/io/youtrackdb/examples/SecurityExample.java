package io.youtrackdb.examples;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import java.util.ArrayList;
import java.util.Map;

/// Fine-Grained Security in YouTrackDB.
///
/// This example demonstrates YouTrackDB's predicate-based security system:
/// users, roles, security policies with per-operation predicates, permission
/// grants/revocations, and policy lifecycle management.
///
/// Companion guide: {@code docs/security.md}
public class SecurityExample {

  @SuppressWarnings("SystemOut")
  public static void main(String[] args) {
    try (var ytdb = YourTracks.instance(".")) {
      readPolicyExample(ytdb);
      deletePolicyExample(ytdb);
      multipleRolesExample(ytdb);
      alterAndRevokeExample(ytdb);
    }
  }

  // ----------------------------------------------------------------
  // 1. READ policy — filter which records a role can see
  // ----------------------------------------------------------------
  @SuppressWarnings("SystemOut")
  static void readPolicyExample(YouTrackDB ytdb) {
    ytdb.create("sec-read", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin",
        "analyst", "analystpwd", "reader");

    try (var admin = ytdb.openTraversal("sec-read", "admin", "adminpwd")) {
      admin.executeInTx(tx -> {
        tx.command("CREATE CLASS Document EXTENDS V");
        tx.command("CREATE PROPERTY Document.title STRING");
        tx.command("CREATE PROPERTY Document.classification STRING");
      });

      admin.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Document SET title = 'Q1 Report',"
            + " classification = 'public'").iterate();
        tx.yql("CREATE VERTEX Document SET title = 'Salary Data',"
            + " classification = 'confidential'").iterate();
        tx.yql("CREATE VERTEX Document SET title = 'Press Release',"
            + " classification = 'public'").iterate();
        tx.yql("CREATE VERTEX Document SET title = 'Board Minutes',"
            + " classification = 'internal'").iterate();
      });

      // Policy: readers can only see public documents.
      admin.executeInTx(tx -> {
        tx.command("CREATE SECURITY POLICY readPublicOnly"
            + " SET READ = (classification = 'public')");
        tx.command(
            "GRANT POLICY readPublicOnly ON database.class.Document TO reader");
      });

      // Admin sees all 4 documents.
      admin.executeInTx(tx -> {
        var cnt = tx.yql("SELECT COUNT(*) AS cnt FROM Document").next();
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) cnt;
        System.out.println("output:admin docs:" + map.get("cnt"));
      });
    }

    // Analyst (reader) sees only public documents.
    try (var reader = ytdb.openTraversal("sec-read", "analyst", "analystpwd")) {
      reader.executeInTx(tx -> {
        var docs = tx.yql(
            "SELECT title FROM Document ORDER BY title").toList();
        System.out.println("output:analyst docs:" + docs.size());
        for (var row : docs) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          System.out.println("output:analyst visible:" + map.get("title"));
        }
      });
    }
  }

  // ----------------------------------------------------------------
  // 2. DELETE policy — restrict which records can be removed
  // ----------------------------------------------------------------
  @SuppressWarnings("SystemOut")
  static void deletePolicyExample(YouTrackDB ytdb) {
    ytdb.create("sec-delete", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin",
        "editor", "editorpwd", "writer");

    try (var admin = ytdb.openTraversal("sec-delete", "admin", "adminpwd")) {
      admin.executeInTx(tx -> {
        tx.command("CREATE CLASS LogEntry EXTENDS V");
        tx.command("CREATE PROPERTY LogEntry.message STRING");
        tx.command("CREATE PROPERTY LogEntry.level STRING");
      });

      admin.executeInTx(tx -> {
        tx.yql("CREATE VERTEX LogEntry SET message = 'Debug info',"
            + " level = 'debug'").iterate();
        tx.yql("CREATE VERTEX LogEntry SET message = 'System error',"
            + " level = 'error'").iterate();
      });

      // Writers can only delete debug-level log entries.
      admin.executeInTx(tx -> {
        tx.command("CREATE SECURITY POLICY deleteDebugOnly"
            + " SET DELETE = (level = 'debug')");
        tx.command(
            "GRANT POLICY deleteDebugOnly ON database.class.LogEntry TO writer");
      });
    }

    try (var editor = ytdb.openTraversal("sec-delete", "editor", "editorpwd")) {
      // Allowed: deleting a debug entry.
      editor.executeInTx(tx -> {
        tx.yql("DELETE VERTEX LogEntry WHERE level = 'debug'").iterate();
      });
      System.out.println("output:delete debug:ok");

      // Blocked: deleting an error entry.
      try {
        editor.executeInTx(tx -> {
          tx.yql("DELETE VERTEX LogEntry WHERE level = 'error'").iterate();
        });
        System.out.println("output:delete error:ok");
      } catch (Exception e) {
        System.out.println("output:delete error:denied");
      }
    }
  }

  // ----------------------------------------------------------------
  // 3. Multiple roles with different policies
  // ----------------------------------------------------------------
  @SuppressWarnings("SystemOut")
  static void multipleRolesExample(YouTrackDB ytdb) {
    ytdb.create("sec-multi", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin",
        "intern", "internpwd", "reader",
        "manager", "managerpwd", "writer");

    try (var admin = ytdb.openTraversal("sec-multi", "admin", "adminpwd")) {
      admin.executeInTx(tx -> {
        tx.command("CREATE CLASS Project EXTENDS V");
        tx.command("CREATE PROPERTY Project.name STRING");
        tx.command("CREATE PROPERTY Project.department STRING");
      });

      admin.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Project SET name = 'Alpha',"
            + " department = 'engineering'").iterate();
        tx.yql("CREATE VERTEX Project SET name = 'Beta',"
            + " department = 'marketing'").iterate();
        tx.yql("CREATE VERTEX Project SET name = 'Gamma',"
            + " department = 'engineering'").iterate();
      });

      // Different policies for different roles.
      admin.executeInTx(tx -> {
        // Interns can only see engineering projects.
        tx.command("CREATE SECURITY POLICY engineeringOnly"
            + " SET READ = (department = 'engineering')");
        tx.command(
            "GRANT POLICY engineeringOnly ON database.class.Project TO reader");
      });
    }

    // Intern sees only engineering projects.
    try (var intern = ytdb.openTraversal("sec-multi", "intern", "internpwd")) {
      intern.executeInTx(tx -> {
        var projects = tx.yql(
            "SELECT name FROM Project ORDER BY name").toList();
        var names = new ArrayList<String>();
        for (var row : projects) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          names.add((String) map.get("name"));
        }
        System.out.println(
            "output:intern projects:" + String.join(",", names));
      });
    }

    // Manager (writer, no READ policy) sees all projects.
    try (var manager = ytdb.openTraversal(
        "sec-multi", "manager", "managerpwd")) {
      manager.executeInTx(tx -> {
        var projects = tx.yql(
            "SELECT name FROM Project ORDER BY name").toList();
        var names = new ArrayList<String>();
        for (var row : projects) {
          @SuppressWarnings("unchecked")
          var map = (Map<String, Object>) row;
          names.add((String) map.get("name"));
        }
        System.out.println(
            "output:manager projects:" + String.join(",", names));
      });
    }
  }

  // ----------------------------------------------------------------
  // 4. ALTER policy and REVOKE — modify and remove policies at runtime
  // ----------------------------------------------------------------
  @SuppressWarnings("SystemOut")
  static void alterAndRevokeExample(YouTrackDB ytdb) {
    ytdb.create("sec-alter", DatabaseType.MEMORY,
        "admin", "adminpwd", "admin",
        "viewer", "viewerpwd", "reader");

    try (var admin = ytdb.openTraversal("sec-alter", "admin", "adminpwd")) {
      admin.executeInTx(tx -> {
        tx.command("CREATE CLASS Memo EXTENDS V");
        tx.command("CREATE PROPERTY Memo.title STRING");
        tx.command("CREATE PROPERTY Memo.priority STRING");
      });

      admin.executeInTx(tx -> {
        tx.yql("CREATE VERTEX Memo SET title = 'Urgent Fix',"
            + " priority = 'high'").iterate();
        tx.yql("CREATE VERTEX Memo SET title = 'Nice to Have',"
            + " priority = 'low'").iterate();
        tx.yql("CREATE VERTEX Memo SET title = 'Roadmap',"
            + " priority = 'medium'").iterate();
      });

      // Step 1: create a policy allowing only high-priority memos.
      admin.executeInTx(tx -> {
        tx.command("CREATE SECURITY POLICY memoPolicy"
            + " SET READ = (priority = 'high')");
        tx.command(
            "GRANT POLICY memoPolicy ON database.class.Memo TO reader");
      });
    }

    // Viewer sees only high-priority memos.
    try (var viewer = ytdb.openTraversal(
        "sec-alter", "viewer", "viewerpwd")) {
      viewer.executeInTx(tx -> {
        var count = tx.yql("SELECT COUNT(*) AS cnt FROM Memo").next();
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) count;
        System.out.println("output:before alter:" + map.get("cnt"));
      });
    }

    // Step 2: ALTER the policy to also include medium-priority memos.
    try (var admin = ytdb.openTraversal("sec-alter", "admin", "adminpwd")) {
      admin.executeInTx(tx -> {
        tx.command("ALTER SECURITY POLICY memoPolicy"
            + " SET READ = (priority IN ['high', 'medium'])");
      });
    }

    try (var viewer = ytdb.openTraversal(
        "sec-alter", "viewer", "viewerpwd")) {
      viewer.executeInTx(tx -> {
        var count = tx.yql("SELECT COUNT(*) AS cnt FROM Memo").next();
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) count;
        System.out.println("output:after alter:" + map.get("cnt"));
      });
    }

    // Step 3: REVOKE the policy — viewer sees all memos again.
    try (var admin = ytdb.openTraversal("sec-alter", "admin", "adminpwd")) {
      admin.executeInTx(tx -> {
        tx.command(
            "REVOKE POLICY ON database.class.Memo FROM reader");
      });
    }

    try (var viewer = ytdb.openTraversal(
        "sec-alter", "viewer", "viewerpwd")) {
      viewer.executeInTx(tx -> {
        var count = tx.yql("SELECT COUNT(*) AS cnt FROM Memo").next();
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) count;
        System.out.println("output:after revoke:" + map.get("cnt"));
      });
    }
  }
}
