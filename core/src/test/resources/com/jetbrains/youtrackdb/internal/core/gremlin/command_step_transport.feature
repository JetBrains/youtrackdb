Feature: Command Step Transport

  Background:
    Given an empty graph database
    And a vertex with label "User" and name "John"

  Scenario: Execute SQL via Command Step (Embedded)
    Given I am using embedded mode
    When I execute query "SELECT FROM User WHERE name = 'John'"
    Then the command execution should be successful
    And the result should contain 1 vertices

  Scenario: Execute CREATE via Command Step (Embedded)
    Given I am using embedded mode
    When I execute command "CREATE VERTEX User SET name = 'Maria'"
    Then the command execution should be successful
    And the database should contain a vertex with name "Maria"

  Scenario: Execute parameterized SQL via Command Step
    Given I am using embedded mode
    When I execute command "CREATE VERTEX User SET name = :name, age = :age" with params:
      | name | Maria |
      | age  | 30    |
    Then the database should contain a vertex with name "Maria" and age 30

  Scenario: Execute DELETE via Command Step
    Given I am using embedded mode
    And a vertex with label "User" and name "John"
    When I execute command "DELETE VERTEX User WHERE name = 'John'"
    Then the database should not contain a vertex with name "John"

  Scenario: Execute invalid SQL command
    Given I am using embedded mode
    When I execute command "INCORRECT COMMAND"
    Then the command execution should fail