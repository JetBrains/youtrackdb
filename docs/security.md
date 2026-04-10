# Fine-Grained Security

YouTrackDB provides predicate-based security — YQL conditions that are evaluated
per-record to determine whether an operation is allowed. This enables row-level
access control enforced entirely by the database, with no application-side
filtering required.

All examples use the public API and run as embedded in-memory databases.
The complete source code is in
[`SecurityExample.java`](../examples/src/main/java/io/youtrackdb/examples/SecurityExample.java),
with tests in
[`ExamplesTest.java`](../examples/src/test/java/io/youtrackdb/examples/ExamplesTest.java).

## Core Concepts

- **Users** have credentials and are assigned one or more **roles**.
- **Roles** define coarse-grained permissions (admin, writer, reader).
- **Security policies** are sets of YQL predicates bound to specific operations
  (READ, CREATE, DELETE, BEFORE UPDATE, AFTER UPDATE, EXECUTE).
- Policies are **granted** to a role on a resource (e.g., a class) and
  automatically enforced for every record access.

## Predefined Roles

| Role | Permissions |
|---|---|
| `admin` | Full access — all operations on all resources |
| `writer` | Read and modify data, but cannot create or drop classes |
| `reader` | Read-only access to data |

## 1. Users and Roles

Create a database with multiple users by passing credential triples
(username, password, role):

```java
ytdb.create("my-db", DatabaseType.MEMORY,
    "admin", "adminpwd", "admin",
    "analyst", "analystpwd", "reader");
```

Users can also be created after database creation with YQL:

```sql
CREATE USER analyst IDENTIFIED BY analystpwd ROLE reader
```

For more, see [CREATE USER](yql/YQL-Create-User.md) and
[DROP USER](yql/YQL-Drop-User.md).

## 2. READ Policy — Row-Level Filtering

A READ policy controls which records a role can see. The database applies
the predicate automatically to every query.

```java
try (var admin = ytdb.openTraversal("sec-read", "admin", "adminpwd")) {
  // Schema and data setup.
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

  // Create a policy and bind it to the reader role.
  admin.executeInTx(tx -> {
    tx.command("CREATE SECURITY POLICY readPublicOnly"
        + " SET READ = (classification = 'public')");
    tx.command(
        "GRANT POLICY readPublicOnly ON database.class.Document TO reader");
  });

  // Admin sees all 4 documents.
}

// Analyst (reader role) — the policy filters automatically.
try (var reader = ytdb.openTraversal("sec-read", "analyst", "analystpwd")) {
  reader.executeInTx(tx -> {
    var docs = tx.yql("SELECT title FROM Document ORDER BY title").toList();
    // Returns only: Press Release, Q1 Report
    // Salary Data and Board Minutes are invisible to this role.
  });
}
```

## 3. DELETE Policy — Restrict Record Removal

A DELETE policy controls which records a role is allowed to remove.
Records that do not match the predicate cannot be deleted.

```java
// Writers can only delete debug-level log entries.
admin.executeInTx(tx -> {
  tx.command("CREATE SECURITY POLICY deleteDebugOnly"
      + " SET DELETE = (level = 'debug')");
  tx.command(
      "GRANT POLICY deleteDebugOnly ON database.class.LogEntry TO writer");
});

// As editor (writer role):
editor.executeInTx(tx -> {
  tx.yql("DELETE VERTEX LogEntry WHERE level = 'debug'").iterate(); // succeeds
});
try {
  editor.executeInTx(tx -> {
    tx.yql("DELETE VERTEX LogEntry WHERE level = 'error'").iterate(); // blocked
  });
} catch (Exception e) {
  // SecurityException — the policy prevents deletion of error entries
}
```

## 4. Multiple Roles with Different Policies

Different roles can have different policies on the same class. Each user
sees data according to their role's policy.

```java
admin.executeInTx(tx -> {
  // Interns (reader) can only see engineering projects.
  tx.command("CREATE SECURITY POLICY engineeringOnly"
      + " SET READ = (department = 'engineering')");
  tx.command(
      "GRANT POLICY engineeringOnly ON database.class.Project TO reader");
});

// Intern sees: Alpha, Gamma (engineering only)
// Manager (writer, no READ policy) sees: Alpha, Beta, Gamma (all projects)
```

## 5. ALTER Policy — Modify at Runtime

Policies can be changed at runtime without recreating them. The change
takes effect immediately for all subsequent queries.

```java
// Initially: only high-priority memos visible to readers.
admin.executeInTx(tx -> {
  tx.command("CREATE SECURITY POLICY memoPolicy"
      + " SET READ = (priority = 'high')");
  tx.command("GRANT POLICY memoPolicy ON database.class.Memo TO reader");
});
// Viewer sees: 1 memo (high only)

// Widen the policy to include medium-priority memos.
admin.executeInTx(tx -> {
  tx.command("ALTER SECURITY POLICY memoPolicy"
      + " SET READ = (priority IN ['high', 'medium'])");
});
// Viewer sees: 2 memos (high + medium)
```

## 6. REVOKE Policy — Remove Access Restrictions

Revoking a policy restores the default role permissions for that resource.

```java
admin.executeInTx(tx -> {
  tx.command("REVOKE POLICY ON database.class.Memo FROM reader");
});
// Viewer now sees all 3 memos — no policy filtering applied.
```

## Supported Policy Operations

A security policy can include predicates for any combination of these
operations:

| Operation | When evaluated |
|---|---|
| `READ` | On every record returned by a query |
| `CREATE` | When inserting a new record |
| `BEFORE UPDATE` | Before a record is modified (checks the old state) |
| `AFTER UPDATE` | After a record is modified (checks the new state) |
| `DELETE` | When removing a record |
| `EXECUTE` | When executing a function |

Multiple operations can be set in a single policy:

```sql
CREATE SECURITY POLICY fullControl
  SET READ = (classification = 'public'),
      CREATE = (status = 'draft'),
      DELETE = (status = 'archived')
```

## Permission Grants

In addition to policies (predicate-based, per-record), YouTrackDB supports
coarse-grained permission grants on resources:

```sql
-- Grant read access on a specific class
GRANT READ ON database.class.Report TO analyst

-- Grant all permissions on the database
GRANT ALL ON database TO admin

-- Revoke delete permission
REVOKE DELETE ON database.class.AuditLog FROM writer
```

## YQL Reference

- [CREATE SECURITY POLICY](yql/YQL-Create-Security-Policy.md)
- [ALTER SECURITY POLICY](yql/YQL-Alter-Security-Policy.md)
- [GRANT](yql/YQL-Grant.md)
- [REVOKE](yql/YQL-Revoke.md)
- [CREATE USER](yql/YQL-Create-User.md)
- [DROP USER](yql/YQL-Drop-User.md)
