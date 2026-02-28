package io.youtrackdb.examples;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Starts a YouTrackDB server in a Docker container via Testcontainers, then runs
 * {@link RemoteExample#main(String[])} against it and verifies the stdout output.
 *
 * <p>This test is excluded from the default surefire run and only included when
 * the {@code docker-images} Maven profile is active (which also builds the Docker image).
 */
public class RemoteExampleTest {

  private static final String ROOT_PASSWORD = "root";

  @SuppressWarnings("resource")
  private static final GenericContainer<?> SERVER = new GenericContainer<>(
      "youtrackdb/youtrackdb-server")
      .withExposedPorts(8182)
      .withCopyToContainer(
          Transferable.of(ROOT_PASSWORD),
          "/opt/ytdb-server/secrets/root_password")
      .waitingFor(Wait.forListeningPorts(8182));

  private final PrintStream originalOut = System.out;
  private ByteArrayOutputStream captured;

  @BeforeClass
  public static void startServer() {
    SERVER.start();

    // Configure RemoteExample to connect to the Testcontainers-mapped host/port
    System.setProperty("ytdb.server.host", SERVER.getHost());
    System.setProperty("ytdb.server.port",
        String.valueOf(SERVER.getMappedPort(8182)));
    System.setProperty("ytdb.server.rootPassword", ROOT_PASSWORD);
  }

  @AfterClass
  public static void stopServer() {
    System.clearProperty("ytdb.server.host");
    System.clearProperty("ytdb.server.port");
    System.clearProperty("ytdb.server.rootPassword");
    SERVER.stop();
  }

  @Before
  public void captureStdout() {
    captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured));
  }

  @After
  public void restoreStdout() {
    System.setOut(originalOut);
  }

  /**
   * Runs {@link RemoteExample#main(String[])} against the Testcontainers server and verifies
   * the four expected "output:" lines (same as the embedded examples): marko JSON vertex,
   * "josh", second marko JSON vertex, "lop".
   */
  @Test
  public void remoteExampleProducesExpectedOutput() throws Exception {
    RemoteExample.main(new String[0]);

    ExamplesTest.assertExampleOutput(captured.toString(UTF_8));
  }
}
