package io.youtrackdb.examples;

import com.jetbrains.youtrackdb.api.YourTracks;


/// Example of usage of YouTrackDB in case of embedded deployment.
///
/// To use YouTrackDB as an embedded database, add the following dependency:
/// {@snippet :
///   <dependency>
///     <groupId>io.youtrackdb</groupId>
///     <artifactId>youtrackdb-embedded</artifactId>
///     <version>${ytdb-version}</version>
///   </dependency>
/// }
/// The {@code youtrackdb-embedded} artifact is a shaded uber-jar that relocates
/// third-party dependencies to avoid classpath conflicts with your application.
public class EmbeddedExample extends AbstractExample {

  public static void main(String[] args) throws Exception {
    //Create a YouTrackDB database manager instance and provide the root folder
    //where all databases will be stored
    try (var ytdb = YourTracks.instance(".")) {
      ytdManipulationExample(ytdb);
    }
  }


}
