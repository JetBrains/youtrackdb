/*
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
 */

package com.jetbrains.youtrack.db.api.record;

/**
 * Interface that represents a unique record id in a database.
 * Record id <b>cannot</b> be used outside
 * the database as its value can be changed during the database lifecycle (e.g., after database
 * migration).
 */
public interface RID extends Identifiable {
  char PREFIX = '#';
  char SEPARATOR = ':';
  int CLUSTER_MAX = 32767;
  int CLUSTER_ID_INVALID = -1;
  long CLUSTER_POS_INVALID = -1;

  int getClusterId();

  long getClusterPosition();

  boolean isPersistent();

  boolean isNew();
}
