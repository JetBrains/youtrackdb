@StepCall @YTDBOnly
Feature: GQL Match Support

  Background:
    Given the empty graph
    And the traversal of
      """
      g.sqlCommand("CREATE CLASS GqlPerson IF NOT EXISTS EXTENDS V").sqlCommand("CREATE CLASS GqlWork IF NOT EXISTS EXTENDS V")
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
      | result              |
      | m[{"a":"v[Alice]"}] |

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
    And the result should be unordered
      | result   |
      | v[Alice] |

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
    And the result should be unordered
      | result   |
      | v[Alice] |
      | v[John]  |

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
    And the result should be unordered
      | result                     |
      | m[{"a" : "v[Alice]"}]      |
      | m[{"a" : "v[Programmer]"}] |

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
    And the result should be unordered
      | result   |
      | v[Alice] |

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
    And the result should be unordered
      | result              |
      | m[{"a":"v[John]"}]  |
      | m[{"a":"v[Alice]"}] |

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
    And the result should be unordered
      | result    |
      | v[Alice]  |
      | ["v[John] |

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
      | result                             |
      | m[{"a":"v[John]"}]  |
      | m[{"a":"v[Alice]"}, v[Programmer]] |
      | m[{"a":"v[Alice]"}, v[TeamLeader]] |
      | m[{"a":"v[John]"}, v[TeamLeader]]  |

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
    And the result should be unordered
      | result              |
      | m[{"a":"v[Maria]"}] |

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

  Scenario: g_gql_MATCH_with_empty_pattern_returns_empty_when_no_data
    And the traversal of
      """
      g.gql("MATCH ()")
      """
    When iterated to list
    Then the result should be empty