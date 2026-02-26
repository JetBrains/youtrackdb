package io.youtrackdb.examples;

import com.jetbrains.youtrackdb.api.YourTracks;


/// Example of usage of YouTrackDB in case of embedded deployment.
public class EmbeddedExample extends AbstractExample{

  public static void main(String[] args) throws Exception {
    //Create a YouTrackDB database manager instance and provide the root folder where all databases will be stored
    try (var ytdb = YourTracks.instance(".")) {
      ytdManipulationExample(ytdb);
    }
  }


}
