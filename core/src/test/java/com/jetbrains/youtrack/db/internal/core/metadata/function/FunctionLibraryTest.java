package com.jetbrains.youtrack.db.internal.core.metadata.function;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Test;

/**
 *
 */
public class FunctionLibraryTest extends DbTestBase {

  @Test
  public void testSimpleFunctionCreate() {
    var func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
    assertNotNull(func);
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNotNull(func);
  }

  @Test(expected = FunctionDuplicatedException.class)
  public void testDuplicateFunctionCreate() {
    var func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
    assertNotNull(func);
    session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
  }

  @Test
  public void testFunctionCreateDrop() {
    var func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc");
    assertNotNull(func);
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNotNull(func);
    session.getMetadata().getFunctionLibrary().dropFunction(session, "TestFunc");
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNull(func);
    func = session.getMetadata().getFunctionLibrary().createFunction("TestFunc1");
    session.begin();
    session.getMetadata().getFunctionLibrary().dropFunction(session, func);
    session.commit();
    func = session.getMetadata().getFunctionLibrary().getFunction(session, "TestFunc");
    assertNull(func);
  }
}
