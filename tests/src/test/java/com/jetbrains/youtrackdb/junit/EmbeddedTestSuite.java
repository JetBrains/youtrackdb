package com.jetbrains.youtrackdb.junit;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * JUnit 5 test suite for the embedded database test suite.
 * Classes are listed in the same order as the TestNG suite XML
 * ({@code embedded-test-db-from-scratch.xml}) to preserve execution order
 * and cross-class data dependencies.
 *
 * <p>As test classes are migrated from TestNG to JUnit 5, they are added here
 * and removed from the TestNG XML suite.
 */
@Suite
@SelectClasses({
    SmokeTest.class
})
@SuiteDisplayName("Paginated Local Test Suite")
public class EmbeddedTestSuite {
}
