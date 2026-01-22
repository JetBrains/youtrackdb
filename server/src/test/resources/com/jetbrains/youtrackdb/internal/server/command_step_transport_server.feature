Feature: Command Step Transport (Server Mode)

  Background:
    Given an empty graph database on server
    And a vertex with label "User" and name "John" on server

  Scenario: Execute SQL via Command Step (Server)
    Given I am using server mode
    When I execute query "SELECT FROM User WHERE name = 'John'" on server
    Then the command execution should be successful

  Scenario: Execute CREATE via Command Step (Server)
    Given I am using server mode
    When I execute command "CREATE VERTEX User SET name = 'Maria'" on server
    Then the command execution should be successful
    And the database should contain a vertex with name "Maria" on server

  Scenario: Execute invalid SQL command (Server)
    Given I am using server mode
    When I execute command "INCORRECT COMMAND" on server
    Then the command execution should fail
