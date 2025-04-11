package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.client.remote.db.DatabaseSessionRemote;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImpExpAbstract;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImportException;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseTool;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 *
 */
public class DatabaseImportRemote extends DatabaseImpExpAbstract {

  private String options;

  public DatabaseImportRemote(
      DatabaseSessionInternal iDatabase, String iFileName, CommandOutputListener iListener) {
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
  public DatabaseTool setOptions(String iOptions) {
    this.options = iOptions;
    return super.setOptions(iOptions);
  }

  public void importDatabase() throws DatabaseImportException {
    var storage = (StorageRemote) getDatabase().getStorage();
    var file = new File(getFileName());
    try {
      storage.importDatabase((DatabaseSessionRemote) session, options, new FileInputStream(file),
          file.getName(),
          getListener());
    } catch (FileNotFoundException e) {
      throw BaseException.wrapException(
          new DatabaseImportException("Error importing the database"), e, storage.getName());
    }
  }

  public void close() {
  }
}
