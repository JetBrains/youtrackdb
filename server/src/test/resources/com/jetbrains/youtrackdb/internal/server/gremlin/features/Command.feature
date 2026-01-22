@StepCall @YTDBOnly
Feature: Command Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.command("CREATE CLASS Employee IF NOT EXISTS EXTENDS V")
      """
    When iterated to list

  Scenario: g_commandXINSERT_via_Gremlin_and_verify
    And the traversal of
      """
      g.addV("Employee").property("name", "Alice").property("age", 30)
      """
    When iterated next
    And the traversal of
      """
      g.V().hasLabel("Employee").has("name", "Alice")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_commandXDROP_CLASSX
    And the traversal of
      """
      g.command("CREATE CLASS Temporary IF NOT EXISTS EXTENDS V")
      """
    When iterated to list
    And the traversal of
      """
      g.command("DROP CLASS Temporary")
      """
    When iterated to list