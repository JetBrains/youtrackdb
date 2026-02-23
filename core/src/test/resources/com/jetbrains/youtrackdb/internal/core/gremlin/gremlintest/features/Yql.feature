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
