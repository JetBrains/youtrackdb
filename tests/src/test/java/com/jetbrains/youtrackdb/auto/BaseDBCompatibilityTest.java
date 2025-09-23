package com.jetbrains.youtrackdb.auto;

import org.testng.annotations.Test;

public class BaseDBCompatibilityTest {

  private final BaseDBCompatibilityChecker checker = new BaseDBCompatibilityChecker();

  @Test
  public void shouldLoadOldDb() throws Exception {
    checker.shouldLoadOldDb();
  }
}