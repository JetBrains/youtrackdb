package io.youtrackdb.examples;

import com.jetbrains.youtrackdb.api.YourTracks;

/// Example of usage of YouTrackDB in case of remote (server) deployment.
///
/// To connect to a remote YouTrackDB server, add the driver dependency:
/// {@snippet :
///   <dependency>
///     <groupId>io.youtrackdb</groupId>
///     <artifactId>youtrackdb-driver</artifactId>
///     <version>${ytdb-version}</version>
///   </dependency>
/// }
public class RemoteExample extends AbstractExample {
  public static void main(String[] args) throws Exception {
    //1. Run YTDB server using command:
    // 'docker run -p 8182:8182 \
    // -v $(pwd)/secrets:/opt/ytdb-server/secrets \
    // -v $(pwd)/databases:/opt/ytdb-server/databases \
    // -v $(pwd)/conf:/opt/ytdb-server/conf \
    // -v $(pwd)/log:/opt/ytdb-server/log \
    // youtrackdb/youtrackdb-server'
    //2. Create a YouTrackDB database manager instance by connection to the YTDB server.
    try (var ytdb = YourTracks.instance("localhost", "root", "root")) {
      ytdManipulationExample(ytdb);
    }
  }
}
