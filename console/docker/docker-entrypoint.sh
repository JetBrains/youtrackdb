#!/bin/bash
#
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

set -e

# If running as root, fix permissions and switch user
if [ "$(id -u)" = '0' ]; then
    # Get the owner of the databases directory (assumed to be mounted from host)
    # If databases directory doesn't exist yet, we check the parent or assume root/default
    if [ -d "/opt/ytdb-console/databases" ]; then
        DB_UID=$(stat -c "%u" /opt/ytdb-console/databases)
        DB_GID=$(stat -c "%g" /opt/ytdb-console/databases)
    else
         # Fallback if the directory isn't mounted/created yet, though VOLUME instruction usually creates it.
         DB_UID="0"
    fi

    # If the directory is owned by root, just run as root (or existing behavior)
    if [ "$DB_UID" != "0" ]; then
        # Update the 'ytdb' user to match the host user's UID/GID
        usermod -o -u "$DB_UID" ytdb
        groupmod -o -g "$DB_GID" ytdb

        # Ensure internal writable directories are owned by this new UID
        chown -R ytdb:ytdb /opt/ytdb-console/bin
        chown -R ytdb:ytdb /opt/ytdb-console/conf
        chown -R ytdb:ytdb /opt/ytdb-console/databases

        # Drop privileges and run as ytdb
        exec gosu ytdb /opt/ytdb-console/bin/ytdb.sh "$@"
    fi
fi

exec /opt/ytdb-console/bin/ytdb.sh "$@"