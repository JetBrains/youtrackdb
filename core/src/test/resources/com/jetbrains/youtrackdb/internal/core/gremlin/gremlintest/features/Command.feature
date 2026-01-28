@StepCall @YTDBOnly
Feature: Command Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("CREATE CLASS Employee IF NOT EXISTS EXTENDS V").sqlCommand("COMMIT")
      """
    When iterated to list

  Scenario: g_sqlCommand_INSERT_and_verify
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = 'Alice'").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Employee")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_sqlCommand_INSERT_with_parameters
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("INSERT INTO Employee SET name = :name", "name", "Bob").sqlCommand("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Employee").has("name", "Bob")
      """
    When iterated to list
    Then the result should have a count of 1