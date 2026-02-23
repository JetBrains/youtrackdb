@StepCall @YTDBOnly
Feature: Command Support

  Background:
    Given the empty graph
    # DDL is not transactional - execute directly without BEGIN/COMMIT
    And the traversal of
      """
      g.yql("CREATE CLASS Employee IF NOT EXISTS EXTENDS V")
      """
    When iterated to list

  Scenario: g_yql_INSERT_and_verify
    And the traversal of
      """
      g.yql("BEGIN").yql("INSERT INTO Employee SET name = 'Alice'").yql("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Employee")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_yql_INSERT_with_parameters
    And the traversal of
      """
      g.yql("BEGIN").yql("INSERT INTO Employee SET name = :name", "name", "Bob").yql("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Employee").has("name", "Bob")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_yql_INSERT_with_multiple_parameters
    And the traversal of
      """
      g.yql("BEGIN").yql("INSERT INTO Employee SET name = :name, age = :age", "name", "Charlie", "age", 30).yql("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Employee").has("name", "Charlie").has("age", 30)
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_yql_DELETE_with_parameters
    # First insert a record
    And the traversal of
      """
      g.yql("BEGIN").yql("INSERT INTO Employee SET name = 'ToDelete'").yql("COMMIT")
      """
    When iterated to list
    # Verify it exists
    And the traversal of
      """
      g.V().hasLabel("Employee").has("name", "ToDelete")
      """
    When iterated to list
    Then the result should have a count of 1
    # Delete with parameter
    And the traversal of
      """
      g.yql("BEGIN").yql("DELETE VERTEX FROM Employee WHERE name = :name", "name", "ToDelete").yql("COMMIT")
      """
    When iterated to list
    # Verify it's gone
    And the traversal of
      """
      g.V().hasLabel("Employee").has("name", "ToDelete")
      """
    When iterated to list
    Then the result should have a count of 0

  Scenario: g_sqlCommand_SELECT_returns_elements
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Alice'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("SELECT FROM Employee WHERE name = 'Alice'").hasLabel("Employee").values("name")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | Alice  |

  Scenario: g_sqlCommand_SELECT_with_parameters
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Bob'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("SELECT FROM Employee WHERE name = :name", "name", "Bob").hasLabel("Employee").values("name")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | Bob    |

  Scenario: g_sqlCommand_SELECT_empty_result
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("SELECT FROM Employee WHERE name = 'Missing'")
      """
    When iterated to list
    Then the result should have a count of 0

  Scenario: g_sqlCommand_SELECT_projection
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Eve'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("SELECT name FROM Employee WHERE name = 'Eve'")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_sqlCommand_MATCH_returns_vertex_elements
    # Setup: two employees connected by a Knows edge
    And the traversal of
      """
      g.sqlCommand("CREATE CLASS Knows IF NOT EXISTS EXTENDS E")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Alice'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Bob'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("CREATE EDGE Knows FROM (SELECT FROM Employee WHERE name = 'Alice') TO (SELECT FROM Employee WHERE name = 'Bob')").sqlCommand("COMMIT")
      """
    When iterated to list
    # MATCH returning a vertex: should be wrapped as a Gremlin vertex element
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("MATCH {class: Employee, as: p, where: (name = 'Alice')}.out('Knows'){as: friend} RETURN expand(friend)").hasLabel("Employee").values("name")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | Bob    |

  Scenario: g_sqlCommand_MATCH_returns_multiple_vertices
    # Setup: Alice knows both Bob and Charlie
    And the traversal of
      """
      g.sqlCommand("CREATE CLASS Knows IF NOT EXISTS EXTENDS E")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Alice'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Bob'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Charlie'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("CREATE EDGE Knows FROM (SELECT FROM Employee WHERE name = 'Alice') TO (SELECT FROM Employee WHERE name = 'Bob')").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("CREATE EDGE Knows FROM (SELECT FROM Employee WHERE name = 'Alice') TO (SELECT FROM Employee WHERE name = 'Charlie')").sqlCommand("COMMIT")
      """
    When iterated to list
    # MATCH returning multiple vertices
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("MATCH {class: Employee, as: p, where: (name = 'Alice')}.out('Knows'){as: friend} RETURN expand(friend)").hasLabel("Employee").values("name")
      """
    When iterated to list
    Then the result should be unordered
      | result  |
      | Bob     |
      | Charlie |

