@StepCall @YTDBOnly
Feature: Command Support with Manual Transactions

  Scenario: g_command_simple_insert
    Given the empty graph
    And the traversal of
      """
      g.command("CREATE CLASS Person EXTENDS V")
      """
    When iterated to list
    And the traversal of
      """
      g.command("INSERT INTO Person SET name = 'Sandra'")
      """
    When iterated to list
    And the traversal of
      """
      g.V().has("Person", "name", "Sandra").count()
      """
    When iterated to list
    Then the result should have a count of 1