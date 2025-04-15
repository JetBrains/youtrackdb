/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.config;

import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import java.io.Serializable;
import javax.annotation.Nullable;

@SuppressWarnings("serial")
public class StorageSegmentConfiguration implements Serializable {

  public transient StorageConfiguration root;
  public volatile int id;
  public volatile String name;
  public volatile String maxSize = "0";
  public volatile String fileType = "mmap";
  public volatile String fileStartSize = "500Kb";
  public volatile String fileMaxSize = "500Mb";
  public volatile String fileIncrementSize = "50%";
  public volatile String defrag = "auto";
  public volatile STATUS status = STATUS.ONLINE;
  public StorageFileConfiguration[] infoFiles;
  protected String location;

  public enum STATUS {
    ONLINE,
    OFFLINE
  }

  public StorageSegmentConfiguration() {
    infoFiles = CommonConst.EMPTY_FILE_CONFIGURATIONS_ARRAY;
  }

  public void setRoot(StorageConfiguration iRoot) {
    this.root = iRoot;
    for (var f : infoFiles) {
      f.parent = this;
    }
  }

  @Nullable
  public String getLocation() {
    if (location != null) {
      return location;
    }

    return root != null ? root.getDirectory() : null;
  }
}
