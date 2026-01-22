@StepCall @YTDBOnly
Feature: Command Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.call("ytdbCommand", [command: "CREATE CLASS Employee IF NOT EXISTS EXTENDS V"])
      """
    When iterated to list

  Scenario: g_commandXINSERT_and_verify
    And the traversal of
      """
      g.addV("Employee").property("name", "Alice")
      """
    When iterated next
    And the traversal of
      """
      g.V().hasLabel("Employee").count()
      """
    When iterated to list
    Then the result should have a count of 1