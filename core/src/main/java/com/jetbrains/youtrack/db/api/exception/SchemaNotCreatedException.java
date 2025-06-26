package com.jetbrains.youtrack.db.api.exception;

public class SchemaNotCreatedException extends SchemaException implements HighLevelException {

  public SchemaNotCreatedException(String dbName, String message) {
    super(dbName, message);
  }

  /**
   * This constructor is needed to restore and reproduce exception on client side in case of remote
   * storage exception handling. Please create "copy constructor" for each exception which has
   * current one as a parent.
   *
   * @param exception
   */
  public SchemaNotCreatedException(SchemaNotCreatedException exception) {
    super(exception);
  }
}
