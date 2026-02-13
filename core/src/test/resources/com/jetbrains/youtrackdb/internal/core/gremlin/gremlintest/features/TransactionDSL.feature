@StepCall @YTDBOnly
Feature: Transaction DSL Steps - begin(), commit(), rollback()

  Background:
    Given the empty graph

  Scenario: g_addV_begin_commit - Single vertex with transaction
    And the traversal of
      """
      g.addV("Person").property("name", "John").begin().commit()
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Person").has("name", "John")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_addV_begin_addV_commit - Multiple vertices in one transaction
    And the traversal of
      """
      g.addV("Person").property("name", "Alice").begin().addV("Person").property("name", "Bob").commit()
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Person")
      """
    When iterated to list
    Then the result should have a count of 2

  Scenario: g_addV_begin_property_commit - Adding properties within transaction
    And the traversal of
      """
      g.addV("Person").property("name", "Charlie").begin().property("age", 30).property("city", "NYC").commit()
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Person").has("name", "Charlie").has("age", 30).has("city", "NYC")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_V_begin_property_commit - Updating existing vertex
    # First create a vertex
    And the traversal of
      """
      g.addV("Person").property("name", "Diana").property("age", 25).begin().commit()
      """
    When iterated to list
    # Then update it
    And the traversal of
      """
      g.V().hasLabel("Person").has("name", "Diana").begin().property("age", 26).commit()
      """
    When iterated to list
    # Verify the update
    And the traversal of
      """
      g.V().hasLabel("Person").has("name", "Diana").values("age")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | d[26].i |

  Scenario: g_addV_begin_addE_commit - Creating edges within transaction
    # Create two vertices
    And the traversal of
      """
      g.addV("Person").property("name", "Eve").begin().commit()
      """
    When iterated to list
    And the traversal of
      """
      g.addV("Person").property("name", "Frank").begin().commit()
      """
    When iterated to list
    # Create edge between them
    And the traversal of
      """
      g.V().hasLabel("Person").has("name", "Eve").as("eve").V().hasLabel("Person").has("name", "Frank").begin().addE("knows").from("eve").commit()
      """
    When iterated to list
    # Verify edge exists
    And the traversal of
      """
      g.E().hasLabel("knows")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_addV_begin_rollback - Rollback discards changes
    And the traversal of
      """
      g.addV("Person").property("name", "Ghost").begin().rollback()
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Person")
      """
    When iterated to list
    Then the result should have a count of 0

  Scenario: g_V_begin_count_commit - Read operations within transaction
    # Create test data
    And the traversal of
      """
      g.addV("Person").property("name", "Grace").begin().addV("Person").property("name", "Henry").commit()
      """
    When iterated to list
    # Count within transaction
    And the traversal of
      """
      g.V().hasLabel("Person").begin().count().commit()
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | d[2].l |

  Scenario: g_autoExecuteInTx - Using autoExecuteInTx helper method
    # Note: This scenario tests the Java API, not pure Gremlin
    # It would be executed via g.executeInTx() or similar wrapper
    And the traversal of
      """
      g.addV("Company").property("name", "Acme").begin().addV("Company").property("name", "TechCorp").commit()
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Company")
      """
    When iterated to list
    Then the result should have a count of 2

  Scenario: g_multiple_sequential_transactions - Multiple independent transactions
    # First transaction
    And the traversal of
      """
      g.addV("City").property("name", "New York").begin().commit()
      """
    When iterated to list
    # Second transaction
    And the traversal of
      """
      g.addV("City").property("name", "London").begin().commit()
      """
    When iterated to list
    # Third transaction
    And the traversal of
      """
      g.addV("City").property("name", "Tokyo").begin().commit()
      """
    When iterated to list
    # Verify all committed
    And the traversal of
      """
      g.V().hasLabel("City")
      """
    When iterated to list
    Then the result should have a count of 3

  Scenario: g_begin_multiple_operations_commit - Complex transaction with multiple operations
    And the traversal of
      """
      g.addV("Department").property("name", "Engineering").begin().property("budget", 100000).addV("Department").property("name", "Sales").property("budget", 50000).commit()
      """
    When iterated to list
    And the traversal of
      """
      g.V().hasLabel("Department")
      """
    When iterated to list
    Then the result should have a count of 2
    And the traversal of
      """
      g.V().hasLabel("Department").has("name", "Engineering").values("budget")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | d[100000].i |
