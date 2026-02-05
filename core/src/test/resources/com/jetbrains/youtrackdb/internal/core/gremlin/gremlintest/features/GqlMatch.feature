@StepCall @YTDBOnly
Feature: GQL Match Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.sqlCommand("CREATE CLASS GqlPerson IF NOT EXISTS EXTENDS V")
      """
    When iterated to list

  Scenario: g_gql_MATCH_non_existent_class_throws_exception
    And the traversal of
      """
      g.gql("MATCH (a:NonExistentClass)")
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "NonExistentClass"

  Scenario: g_gql_MATCH_with_alias_returns_map_binding
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 1
    And the result should be unordered
      | result |
      | m[{"a":"v[Alice]"}] |

  Scenario: g_gql_MATCH_with_parameters_and_where
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson) WHERE a.name = :name",  "name", "John")
      """
    When iterated to list
    Then the result should have a count of 1
    And the result should be unordered
      | result |
      | m[{"a":"v[John]"}] |

  Scenario: g_gql_MATCH_with_select_and_values
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson)").select("a").values("name")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | Alice  |

  Scenario: g_gql_MATCH_returns_empty_when_no_data
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson)")
      """
    When iterated to list
    Then the result should be empty