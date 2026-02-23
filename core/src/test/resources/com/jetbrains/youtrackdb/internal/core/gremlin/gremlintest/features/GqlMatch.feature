@StepCall @YTDBOnly
Feature: GQL Match Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.yql("CREATE CLASS GqlPerson IF NOT EXISTS EXTENDS V").yql("CREATE CLASS GqlWork IF NOT EXISTS EXTENDS V")
      """
    When iterated to list

  Scenario: g_gql_MATCH_non_existent_class_throws_exception
    And the traversal of
      """
      g.gql("MATCH (a:NonExistentClass)")
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "NonExistentClass"

  # Single binding: per spec isVertex → Gremlin vertex (not Map)
  Scenario: g_gql_MATCH_with_alias_returns_vertex
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

  Scenario: g_gql_MATCH_without_alias_returns_vertex
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_gql_MATCH_without_alias_returns_vertices
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice").addV("GqlPerson").property("name", "John")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 2

  Scenario: g_gql_MATCH_without_label_returns_all_vertices
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice").addV("GqlWork").property("name", "Programmer")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a)")
      """
    When iterated to list
    Then the result should have a count of 2

  Scenario: g_gql_MATCH_with_empty_pattern
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH ()")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_gql_MATCH_multiple_nodes
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John").addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 2

  Scenario: g_gql_MATCH_with_empty_pattern_with_multiple_nodes
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice").addV("GqlPerson").property("name", "John")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH ()")
      """
    When iterated to list
    Then the result should have a count of 2

  Scenario: g_gql_MATCH_multiple_patterns
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John").addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson), (b:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 4
    And the result should be unordered
      | result                              |
      | m[{"a":"v[John]", "b":"v[John]"}]   |
      | m[{"a":"v[Alice]", "b":"v[John]"}]  |
      | m[{"a":"v[Alice]", "b":"v[Alice]"}] |
      | m[{"a":"v[John]", "b":"v[Alice]"}]  |

  Scenario: g_gql_MATCH_three_patterns
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John").addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson), (b:GqlPerson), (c:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 8
    And the result should be unordered
      | result                                              |
      | m[{"a":"v[John]", "b":"v[John]", "c":"v[John]"}]    |
      | m[{"a":"v[Alice]", "b":"v[John]", "c":"v[John]"}]   |
      | m[{"a":"v[Alice]", "b":"v[Alice]", "c":"v[John]"}]  |
      | m[{"a":"v[John]", "b":"v[Alice]", "c":"v[John]"}]   |
      | m[{"a":"v[John]", "b":"v[John]", "c":"v[Alice]"}]   |
      | m[{"a":"v[Alice]", "b":"v[John]", "c":"v[Alice]"}]  |
      | m[{"a":"v[Alice]", "b":"v[Alice]", "c":"v[Alice]"}] |
      | m[{"a":"v[John]", "b":"v[Alice]", "c":"v[Alice]"}]  |

  Scenario: g_gql_MATCH_multiple_patterns_different_labels
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John").addV("GqlPerson").property("name", "Alice")
      .addV("GqlWork").property("name", "Programmer").addV("GqlWork").property("name", "TeamLeader")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson), (b:GqlWork)")
      """
    When iterated to list
    Then the result should have a count of 4
    And the result should be unordered
      | result                                   |
      | m[{"a":"v[John]", "b":"v[Programmer]"}]  |
      | m[{"a":"v[Alice]", "b":"v[Programmer]"}] |
      | m[{"a":"v[Alice]", "b":"v[TeamLeader]"}] |
      | m[{"a":"v[John]", "b":"v[TeamLeader]"}]  |

  Scenario: g_gql_MATCH_multiple_patterns_one_without_alias
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John").addV("GqlPerson").property("name", "Alice")
      .addV("GqlWork").property("name", "Programmer").addV("GqlWork").property("name", "TeamLeader")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson), (:GqlWork)")
      """
    When iterated to list
    Then the result should have a count of 4
    And the result should be unordered
      | result                                     |
      | m[{"a":"v[John]", "$c0":"v[Programmer]"}]  |
      | m[{"a":"v[Alice]", "$c0":"v[Programmer]"}] |
      | m[{"a":"v[Alice]", "$c0":"v[TeamLeader]"}] |
      | m[{"a":"v[John]", "$c0":"v[TeamLeader]"}]  |

  Scenario: g_gql_MATCH_multiple_patterns_without_alias
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John").addV("GqlPerson").property("name", "Alice")
      .addV("GqlWork").property("name", "Programmer").addV("GqlWork").property("name", "TeamLeader")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (:GqlPerson), (:GqlWork)")
      """
    When iterated to list
    Then the result should have a count of 4
    And the result should be unordered
      | result                                       |
      | m[{"$c0":"v[John]", "$c1":"v[Programmer]"}]  |
      | m[{"$c0":"v[Alice]", "$c1":"v[Programmer]"}] |
      | m[{"$c0":"v[Alice]", "$c1":"v[TeamLeader]"}] |
      | m[{"$c0":"v[John]", "$c1":"v[TeamLeader]"}]  |

  Scenario: g_gql_MATCH_multiple_patterns_without_labels
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "John").addV("GqlPerson").property("name", "Alice")
      .addV("GqlWork").property("name", "Programmer").addV("GqlWork").property("name", "TeamLeader")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a), (b)")
      """
    When iterated to list
    Then the result should have a count of 16
    And the result should be unordered
      | result                                        |
      | m[{"a":"v[John]", "b":"v[Programmer]"}]       |
      | m[{"a":"v[Alice]", "b":"v[Programmer]"}]      |
      | m[{"a":"v[Alice]", "b":"v[TeamLeader]"}]      |
      | m[{"a":"v[John]", "b":"v[TeamLeader]"}]       |
      | m[{"a":"v[John]", "b":"v[John]"}]             |
      | m[{"a":"v[Alice]", "b":"v[John]"}]            |
      | m[{"a":"v[Alice]", "b":"v[Alice]"}]           |
      | m[{"a":"v[John]", "b":"v[Alice]"}]            |
      | m[{"a":"v[Programmer]", "b":"v[Programmer]"}] |
      | m[{"a":"v[TeamLeader]", "b":"v[Programmer]"}] |
      | m[{"a":"v[TeamLeader]", "b":"v[TeamLeader]"}] |
      | m[{"a":"v[Programmer]", "b":"v[TeamLeader]"}] |
      | m[{"a":"v[Programmer]", "b":"v[Alice]"}]      |
      | m[{"a":"v[TeamLeader]", "b":"v[Alice]"}]      |
      | m[{"a":"v[TeamLeader]", "b":"v[John]"}]       |
      | m[{"a":"v[Programmer]", "b":"v[John]"}]       |

  Scenario: g_gql_MATCH_with_parameters_and_where
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Maria")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson) WHERE a.name = $name",  "name", "Maria")
      """
    When iterated to list
    Then the result should have a count of 1

  # Single binding returns vertex; get property with values("name")
  Scenario: g_gql_MATCH_with_select_and_values
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson)").values("name")
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

  Scenario: g_gql_MATCH_with_empty_pattern_returns_empty_when_no_data
    And the traversal of
      """
      g.gql("MATCH ()")
      """
    When iterated to list
    Then the result should be empty

  Scenario: g_gql_MATCH_in_streaming_mode_after_V
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "StreamingAlice")
      """
    When iterated to list
    And the traversal of
      """
      g.V().gql("MATCH (a:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 1

  Scenario: g_gql_MATCH_second_pattern_non_existent_class_throws_exception
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "Alice")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson), (b:NonExistentClass2)")
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "NonExistentClass2"

  Scenario: g_gql_MATCH_partial_consumption_with_limit
    And the traversal of
      """
      g.addV("GqlPerson").property("name", "A").addV("GqlPerson").property("name", "B")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson), (b:GqlPerson)").limit(2)
      """
    When iterated to list
    Then the result should have a count of 2

  Scenario: g_gql_MATCH_multiple_patterns_returns_empty_when_no_data
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson), (b:GqlWork)")
      """
    When iterated to list
    Then the result should be empty

  Scenario: g_gql_MATCH_polymorphic_subclass
    And the traversal of
      """
      g.yql("CREATE CLASS GqlEmployee IF NOT EXISTS EXTENDS GqlPerson")
      """
    When iterated to list
    And the traversal of
      """
      g.addV("GqlEmployee").property("name", "Bob")
      """
    When iterated to list
    And the traversal of
      """
      g.gql("MATCH (a:GqlPerson)")
      """
    When iterated to list
    Then the result should have a count of 1