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
package com.jetbrains.youtrack.db.internal.core.metadata.function;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ProxedResource;
import java.util.Set;

/**
 * Proxy class to access to the centralized Function Library instance.
 */
public class FunctionLibraryProxy extends ProxedResource<FunctionLibraryImpl>
    implements FunctionLibrary {

  public FunctionLibraryProxy(
      final FunctionLibraryImpl iDelegate, final DatabaseSessionInternal iDatabase) {
    super(iDelegate, iDatabase);
  }

  @Override
  public Set<String> getFunctionNames() {
    return delegate.getFunctionNames();
  }

  @Override
  public Function getFunction(DatabaseSessionInternal db, final String iName) {
    return delegate.getFunction(db, iName);
  }

  @Override
  public Function createFunction(final String iName) {
    return delegate.createFunction(session, iName);
  }

  @Override
  public void create() {
    FunctionLibraryImpl.create(session);
  }

  @Override
  public void load() {
    delegate.load(session);
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public void dropFunction(DatabaseSession session, Function function) {
    delegate.dropFunction((DatabaseSessionInternal) session, function);
  }

  @Override
  public void dropFunction(DatabaseSession session, String iName) {
    delegate.dropFunction((DatabaseSessionInternal) session, iName);
  }
}
