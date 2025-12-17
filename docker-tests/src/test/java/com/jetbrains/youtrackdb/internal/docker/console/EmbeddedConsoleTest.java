package com.jetbrains.youtrackdb.internal.docker.console;

import com.github.dockerjava.api.model.PortBinding;
import io.github.classgraph.ClassGraph;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@RunWith(Parameterized.class)
public class EmbeddedConsoleTest {

  private final String scriptFilePath;
  private final String scriptName;

  public EmbeddedConsoleTest(String scriptFileName, String scriptName) {
    this.scriptFilePath = scriptFileName;
    this.scriptName = scriptName;
  }

  @Test
  public void testScripts() {
    var logConsumer = new ToStringConsumer();
    var debug = Boolean.getBoolean("ytdb.testcontainer.debug.container");
    try (var console = new GenericContainer<>(
        DockerImageName.parse("youtrackdb/youtrackdb-console"))) {
      console.withCopyFileToContainer(MountableFile.forClasspathResource(
              scriptFilePath),
          "/" + scriptName);
      console.withCommand("-e", "/" + scriptName);

      if (debug) {
        console.withEnv("JAVA_OPTIONS",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:6006");
        console.withCreateContainerCmdModifier(cmd ->
            cmd.getHostConfig().withPortBindings(
                PortBinding.parse("6006:6006")
            )
        );
        console.withExposedPorts(6006);
      }
      console.withLogConsumer(logConsumer);

      if (!debug) {
        console.withStartupCheckStrategy(
            new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(30))
        );
      } else {
        console.withStartupCheckStrategy(
            new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(30))
        );
      }

      console.start();
      var output = logConsumer.toUtf8String();
      System.out.println("--- Container Output ---");
      System.out.println(output);
      System.out.println("------------------------");
    }
  }

  @Parameters(name = "Test {index}, script name: {1} ")
  public static Collection<Object[]> data() {
    try (var scanResult = new ClassGraph()
        .acceptPaths(EmbeddedConsoleTest.class.getPackageName().replace(".", "/") + "/scripts")
        .scan()) {
      var resources = scanResult.getAllResources();

      return resources.stream().map(resource -> {
            var path = Paths.get(resource.getPath());
            var scriptName = path.getFileName().toString();

            return new Object[]{
                path.toString(), scriptName
            };
          }
      ).toList();
    }
  }
}
