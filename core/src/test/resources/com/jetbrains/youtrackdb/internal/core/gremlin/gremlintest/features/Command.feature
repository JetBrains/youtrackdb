@StepCall @YTDBOnly
Feature: Command Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.sqlCommand("BEGIN").sqlCommand("CREATE CLASS Employee IF NOT EXISTS EXTENDS V").sqlCommand("COMMIT")
      """
    When iterated to list

  Scenario: g_commandXINSERT_and_verify
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