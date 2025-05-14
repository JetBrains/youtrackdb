package com.orientechnologies.distribution.integration;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

public abstract class SingleYouTrackDBServerBaseIT {

  private static final Logger LOGGER = LoggerFactory.getLogger(SingleYouTrackDBServerBaseIT.class);

  @ClassRule
  public static GenericContainer container =
      new GenericContainer("youtrackdb/youtrackdb:latest")
          .withEnv("YOUTRACKDB_ROOT_PASSWORD", "root")
          .withExposedPorts(2480, 2424)
          .waitingFor(Wait.forListeningPort());

  @Rule
  public TestName name = new TestName();

  protected YouTrackDB youTrackDB;
  protected SessionPool pool;

  @BeforeClass
  public static void beforeClass() throws Exception {
    //    System.setProperty("java.util.logging.manager",
    // "org.apache.logging.log4j.jul.LogManager");
    Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(LOGGER);
    container.followOutput(logConsumer);
  }
}
