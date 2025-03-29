package com.jetbrains.youtrack.db.auto;

import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class DBRecordCreateTest extends BaseDBTest {

  @Parameters(value = "remote")
  public DBRecordCreateTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @Test
  public void testNewRecordNoTx() {
    var element = session.newEntity();
    Assert.assertFalse(element.exists());
  }

  @Test
  public void testNewRecordTx() {
    session.begin();
    try {
      var element = session.newEntity();
      Assert.assertFalse(element.exists());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void testSavedRecordTx() {
    session.begin();
    try {
      var element = session.newEntity();
      Assert.assertTrue(element.exists());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void testDeletedRecordTx() {
    session.begin();
    try {
      var element = session.newEntity();
      element.delete();
      Assert.assertFalse(element.exists());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void testSaveDeletedRecordTx() {
    session.begin();
    try {
      var element = session.newEntity();
      Assert.assertTrue(element.exists());
      element.delete();
      Assert.assertFalse(element.exists());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void testLoadedRecordNoTx() {
    session.begin();
    var element = session.newEntity();
    session.commit();

    session.begin();
    var loadedElement = session.load(element.getIdentity());
    Assert.assertTrue(loadedElement.exists());
    session.commit();
  }

  @Test
  public void testLoadedRecordTx() {
    session.begin();
    try {
      var element = session.newEntity();
      var loadedElement = session.load(element.getIdentity());
      Assert.assertTrue(loadedElement.exists());
    } finally {
      session.rollback();
    }
  }

  @Test
  public void testLoadedDeletedRecordTx() {
    session.begin();
    try {
      var element = session.newEntity();
      var loadedElement = session.load(element.getIdentity());
      Assert.assertTrue(loadedElement.exists());
      element.delete();
      Assert.assertFalse(loadedElement.exists());
    } finally {
      session.rollback();
    }
  }
}
