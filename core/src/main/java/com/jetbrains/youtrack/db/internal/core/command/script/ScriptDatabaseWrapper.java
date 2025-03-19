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
package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;

/**
 * Database wrapper class to use from scripts.
 */
public class ScriptDatabaseWrapper {

  protected DatabaseSessionInternal database;

  public ScriptDatabaseWrapper(final DatabaseSessionInternal database) {
    this.database = database;
  }

  public ResultSet query(final String iText, final Object... iParameters) {
    return this.database.query(iText, iParameters);
  }

  public ResultSet query(final String query, Map<String, Object> iParameters) {
    return this.database.query(query, iParameters);
  }

  public ResultSet execute(final String iText, final Object... iParameters) {
    return this.database.execute(iText, iParameters);
  }

  public ResultSet execute(final String query, Map<String, Object> iParameters) {
    return this.database.execute(query, iParameters);
  }

  public void command(final String iText, final Object... iParameters) {
    this.database.command(iText, iParameters);
  }

  public void command(final String query, Map<String, Object> iParameters) {
    this.database.command(query, iParameters);
  }

  public ResultSet runScript(String language, final String script, final Object... iParameters) {
    return this.database.runScript(language, script, iParameters);
  }

  public ResultSet runScript(String language, final String script,
      Map<String, Object> iParameters) {
    return this.database.runScript(language, script, iParameters);
  }

  public Entity newInstance() {
    return this.database.newInstance();
  }

  public Entity newInstance(String className) {
    return this.database.newInstance(className);
  }

  public Vertex newVertex() {
    return this.database.newVertex();
  }

  public Vertex newVertex(String className) {
    return this.database.newVertex(className);
  }

  public Edge newEdge(Vertex from, Vertex to) {
    return this.database.newStatefulEdge(from, to);
  }

  public Edge newEdge(Vertex from, Vertex to, String edgeClassName) {
    return this.database.newStatefulEdge(from, to, edgeClassName);
  }

  public void delete(DBRecord record) {
    this.database.delete(record);
  }

  public void commit() {
    database.commit();
  }

  public void rollback() {
    database.rollback();
  }

  public void begin() {
    database.begin();
  }

  public Blob newBlob() {
    return this.database.newBlob();
  }
}
