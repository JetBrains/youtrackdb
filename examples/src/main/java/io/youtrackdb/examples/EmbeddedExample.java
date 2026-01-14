package io.youtrackdb.examples;

import static org.apache.tinkerpop.gremlin.process.traversal.P.gt;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.gremlin.YTDBDemoGraphFactory;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONMapper;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONVersion;
import org.apache.tinkerpop.shaded.jackson.core.JsonProcessingException;


/// Example of usage of YouTrackDB in case of embedded deployment.
public class EmbeddedExample extends AbstractExample{

  public static void main(String[] args) throws Exception {
    //Create a YouTrackDB database manager instance and provide the root folder where all databases will be stored
    try (var ytdb = YourTracks.instance(".")) {
      ytdManipulationExample(ytdb);
    }
  }


}
