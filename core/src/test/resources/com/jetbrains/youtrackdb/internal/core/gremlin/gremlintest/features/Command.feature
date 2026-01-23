@StepCall @YTDBOnly
Feature: Command Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.command("BEGIN").command("CREATE CLASS Employee IF NOT EXISTS EXTENDS V").command("COMMIT")
      """
    When iterated to list

  Scenario: g_commandXINSERT_and_verify
    And the traversal of
      """
      g.command("BEGIN").command("INSERT INTO Employee SET name = 'Alice'").command("COMMIT")
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Employee")
      """
    When iterated to list
    Then the result should have a count of 1