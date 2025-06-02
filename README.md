## YouTrackDB

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![TeamCity](https://youtrackdb.teamcity.com/app/rest/builds/buildType:(id:YouTrackDB_UnitTests)/statusIcon)](https://youtrackdb.teamcity.com/viewType.html?buildTypeId=YouTrackDB_UnitTests&guest=1) </br>

[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?style=for-the-badge&logo=Bluesky&logoColor=white)](https://bsky.app/profile/youtrackdb.io) [![Medium](https://img.shields.io/badge/Medium-12100E?style=for-the-badge&logo=medium&logoColor=white)](https://medium.com/@youtrackdb)  [![Reddit](https://img.shields.io/badge/Reddit-%23FF4500.svg?style=for-the-badge&logo=Reddit&logoColor=white)](https://www.reddit.com/r/youtrackdb/)<br/>

------

[Issue tracker](https://youtrack.jetbrains.com/issues/YTDB) | [Knowledge base](https://youtrack.jetbrains.com/articles/YTDB) | [Roadmap](https://youtrack.jetbrains.com/articles/YTDB-A-3/Short-term-roadmap)

### What is YouTrackDB?

YouTrackDB is an object-oriented graph database that supports documents, full-text search,
reactivity, and geospatial concepts.\
YouTrackDB has been supported and developed by [YouTrack](https://www.jetbrains.com/youtrack)
project from JetBrains.

YouTrackDB's key features are:

1. **Fast data processing**: Links traversal is processed with O(1) complexity. There are no
   expensive run-time JOINs.
2. **Rich API**: Implements rich graph and object-oriented data models.
3. **SQL-like query language**: Uses a dialect of SQL query language enriched by graph and
   object-oriented functions and commands.
4. **Implementation of TinkerPop API and [Gremlin query language](https://tinkerpop.apache.org/)**:
   YouTrackDB implements rich API and Gremlin Server to work with non-Java clients.
   You can also use Gremlin query language for your queries out of the box.
4. **Scalable development workflow**: YouTrackDB works in schema-less, schema-mixed, and schema-full
   modes.
5. **Strong security**: A strong security profiling system based on user, role, and predicate
   security.
6. **Encryption of data at rest**: Optionally encrypts all data stored on disk.

### Easy to install and use

YouTrackDB can run on any platform without configuration and installation.
The full Server distribution is a few MBs without the demo database.
