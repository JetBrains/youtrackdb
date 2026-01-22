package com.jetbrains.youtrackdb.internal.core.gremlin;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
    features = {"classpath:com/jetbrains/youtrackdb/internal/core/gremlin/command_step_transport.feature"},
    glue = {"com.jetbrains.youtrackdb.internal.core.gremlin"},
    plugin = {"progress", "junit:target/cucumber-command-step-transport.xml"}
)
public class CommandStepFeatureTest {
}
