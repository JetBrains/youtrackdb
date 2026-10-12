# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

@StepClassMap @StepCall @TinkerServiceRegistry
Feature: Custom Service Call Syntax - Direct Method Calls

  # These tests verify that arbitrary identifiers can be used as method names
  # and are converted to call() steps with service validation
  # Services are registered directly in the test using "registering service" step

  Scenario: g_testService_fromSource
    Given the empty graph
    And registering service "testService"
    And the traversal of
      """
      g.testService()
      """
    When iterated to list
    Then the result should be empty

  Scenario: g_V_testService
    Given the modern graph
    And registering service "testService"
    And the traversal of
      """
      g.V().testService()
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_mapX
    Given the modern graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().testService(xx1)
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_traversalX
    Given the modern graph
    And registering service "testService"
    And the traversal of
      """
      g.V().testService(__.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_map_traversalX
    Given the modern graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().testService(xx1, __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  # Tests with argument lists (args)

  Scenario: g_V_testService_args
    Given the modern graph
    And registering service "testService"
    And the traversal of
      """
      g.V().testService("arg1", "arg2")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_args_numeric
    Given the modern graph
    And registering service "testService"
    And the traversal of
      """
      g.V().testService(1, 2, 3)
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_args_mapX
    Given the modern graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().testService("arg1", "arg2", xx1)
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_args_traversalX
    Given the modern graph
    And registering service "testService"
    And the traversal of
      """
      g.V().testService("arg1", "arg2", __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_args_map_traversalX
    Given the modern graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().testService("arg1", "arg2", xx1, __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_args_withMapInMiddle
    Given the modern graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().testService("arg1", xx1, "arg2")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_V_testService_args_withMapInMiddle_numeric
    Given the modern graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().testService(1, xx1, 2, 3)
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | v[marko] |
      | v[vadas] |
      | v[lop] |
      | v[josh] |
      | v[ripple] |
      | v[peter] |

  Scenario: g_testService_args_withMapInMiddle_fromSource
    Given the empty graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.testService("arg1", xx1, "arg2")
      """
    When iterated to list
    Then the result should be empty

  Scenario: g_testService_args_fromSource
    Given the empty graph
    And registering service "testService"
    And the traversal of
      """
      g.testService("arg1", "arg2")
      """
    When iterated to list
    Then the result should be empty

  Scenario: g_testService_args_mapX_fromSource
    Given the empty graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.testService("arg1", "arg2", xx1)
      """
    When iterated to list
    Then the result should be empty

  Scenario: g_testService_args_traversalX_fromSource
    Given the empty graph
    And registering service "testService"
    And the traversal of
      """
      g.testService("arg1", "arg2", __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the result should be empty

  Scenario: g_testService_args_map_traversalX_fromSource
    Given the empty graph
    And registering service "testService"
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.testService("arg1", "arg2", xx1, __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the result should be empty

  # Argument processing tests - service returns the args list

  Scenario: g_argEcho_args_returned_as_list
    Given the empty graph
    And registering service "argEcho" that returns args as list
    And the traversal of
      """
      g.argEcho("arg1", 2, true)
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | l[arg1,d[2].i,true] |

  Scenario: g_argEcho_no_args_returns_empty_list
    Given the empty graph
    And registering service "argEcho" that returns args as list
    And the traversal of
      """
      g.argEcho()
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | l[] |

  Scenario: g_argEcho_args_with_map_parameter
    Given the empty graph
    And registering service "argEcho" that returns args
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.argEcho(xx1, "arg2")
      """
    When iterated to list
    Then the result should be ordered
      | result |
      | l[m[{"x": "y"}],arg2] |

  Scenario: g_V_argEcho_args_streaming
    Given the modern graph
    And registering service "argEcho" that returns args as list
    And the traversal of
      """
      g.V().argEcho("arg1")
      """
    When iterated to list
    Then the result should be unordered
      | result |
      | m[{"args": "l[arg1]", "traverser": "v[marko]"}] |
      | m[{"args": "l[arg1]", "traverser": "v[vadas]"}] |
      | m[{"args": "l[arg1]", "traverser": "v[lop]"}] |
      | m[{"args": "l[arg1]", "traverser": "v[josh]"}] |
      | m[{"args": "l[arg1]", "traverser": "v[ripple]"}] |
      | m[{"args": "l[arg1]", "traverser": "v[peter]"}] |

  # Validation tests - non-existent services should throw exceptions

  Scenario: g_callXnonExistentServiceX_shouldThrowException
    Given the empty graph
    And the traversal of
      """
      g.call("nonExistentService")
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_V_callXnonExistentServiceX_shouldThrowException
    Given the modern graph
    And the traversal of
      """
      g.V().nonExistentService()
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_V_callXnonExistentService_mapX_shouldThrowException
    Given the modern graph
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().nonExistentService(xx1)
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_V_callXnonExistentService_traversalX_shouldThrowException
    Given the modern graph
    And the traversal of
      """
      g.V().nonExistentService(__.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentServiceX_fromSource_shouldThrowException
    Given the empty graph
    And the traversal of
      """
      g.nonExistentService()
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentService_mapX_fromSource_shouldThrowException
    Given the empty graph
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.nonExistentService(xx1)
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentService_traversalX_fromSource_shouldThrowException
    Given the empty graph
    And the traversal of
      """
      g.nonExistentService(__.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentService_map_traversalX_fromSource_shouldThrowException
    Given the empty graph
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.nonExistentService(xx1, __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_V_callXnonExistentService_argsX_shouldThrowException
    Given the modern graph
    And the traversal of
      """
      g.V().nonExistentService("arg1", "arg2")
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_V_callXnonExistentService_args_mapX_shouldThrowException
    Given the modern graph
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().nonExistentService("arg1", "arg2", xx1)
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_V_callXnonExistentService_args_traversalX_shouldThrowException
    Given the modern graph
    And the traversal of
      """
      g.V().nonExistentService("arg1", "arg2", __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_V_callXnonExistentService_args_map_traversalX_shouldThrowException
    Given the modern graph
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.V().nonExistentService("arg1", "arg2", xx1, __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentService_argsX_fromSource_shouldThrowException
    Given the empty graph
    And the traversal of
      """
      g.nonExistentService("arg1", "arg2")
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentService_args_mapX_fromSource_shouldThrowException
    Given the empty graph
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.nonExistentService("arg1", "arg2", xx1)
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentService_args_traversalX_fromSource_shouldThrowException
    Given the empty graph
    And the traversal of
      """
      g.nonExistentService("arg1", "arg2", __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"

  Scenario: g_callXnonExistentService_args_map_traversalX_fromSource_shouldThrowException
    Given the empty graph
    And using the parameter xx1 defined as "m[{\"x\": \"y\"}]"
    And the traversal of
      """
      g.nonExistentService("arg1", "arg2", xx1, __.project("x").by(__.constant("y")))
      """
    When iterated to list
    Then the traversal will raise an error with message containing text of "Unrecognized service: nonExistentService"
