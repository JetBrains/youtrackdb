package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImpExpAbstract;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImportException;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseTool;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 *
 */
public class DatabaseImportRemote extends DatabaseImpExpAbstract<DatabaseSessionRemote> {

  private String options;

  public DatabaseImportRemote(
      DatabaseSessionRemote iDatabase, String iFileName, CommandOutputListener iListener) {
    super(iDatabase, iFileName, iListener);
  }

  @Override
  public void run() {
    try {
      importDatabase();
    } catch (Exception e) {
      LogManager.instance().error(this, "Error during database import", e);
    }
  }

  @Override
  public DatabaseTool<DatabaseSessionRemote> setOptions(String iOptions) {
    this.options = iOptions;
    super.setOptions(iOptions);
    return this;
  }

  public void importDatabase() throws DatabaseImportException {
    var commandOrchestrator = session.getCommandOrchestrator();
    var file = new File(getFileName());
    try {
      commandOrchestrator.importDatabase(session, options,
          new FileInputStream(file),
          file.getName(),
          getListener());
    } catch (FileNotFoundException e) {
      throw BaseException.wrapException(
          new DatabaseImportException("Error importing the database"), e,
          commandOrchestrator.getName());
    }
  }

  public void close() {
  }
}
