package com.jetbrains.youtrackdb.internal.server;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

/**
 * Cucumber test runner for command step feature tests in SERVER mode.
 * 
 * These tests verify that g.command() works correctly when connecting
 * to a remote YouTrackDB server through network.
 */
@RunWith(Cucumber.class)
@CucumberOptions(
    features = {"classpath:com/jetbrains/youtrackdb/internal/server/command_step_transport_server.feature"},
    glue = {
        "com.jetbrains.youtrackdb.internal.server"
    },
    plugin = {"pretty", "html:target/cucumber-reports/server-command-step.html"}
)
public class CommandStepServerFeatureTest {
    // Test runner for command step feature tests in server mode
}
