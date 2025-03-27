package com.jetbrains.youtrack.db.auto;

import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

public abstract class AbstractSelectTest extends BaseDBTest {

  @Parameters(value = "remote")
  protected AbstractSelectTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }
}
