### YTDB Gremlin Console

*Note: This document is an adaptation of the original
[TinkerPop tutorial](https://tinkerpop.apache.org/docs/current/tutorials/the-gremlin-console/).*

The YTDB Gremlin Console (the YTDB Console) serves for a variety of use cases that can meet the
needs of
different types of YTDB users.
This documentation explores the features of the YTDB Console through a number of these different use
cases to hopefully inspire you to new levels of usage.
While a use case may not fit your needs, you may well find it worthwhile to read, as it is possible
that a "feature" will be discussed that may be useful to you.

The following points summarize the key features discussed in each use case:

- Installation
- A learning tool
    - Introducing the toy graphs
    - Finding help for commands
- Application developers
    - Static import of standard methods
    - Result iteration

---

#### Use Case: Installation

YTDB Console can be installed either by downloading a zip archive from the GitHub releases page.

```bash
$ unzip youtrackdb-console-{ytdb-version}.zip
$ cd youtrackdb-console-{ytdb-version}
$ bin/ytdb.sh

         \,,,/
         (o o)
-----oOOo-(3)-oOOo-----
plugin activated: tinkerpop.server
plugin activated: tinkerpop.utilities
plugin activated: jetbrains.youtrackdb
gremlin>
```

or (and that is a recommended way to install the YTDB Console) by using a Docker image.

```bash
$ docker run -it youtrackdb/youtrackdb-console

          \,,,/
         (o o)
-----oOOo-(3)-oOOo-----
plugin activated: tinkerpop.server
plugin activated: tinkerpop.utilities
plugin activated: jetbrains.youtrackdb
gremlin>
```

As you can see, the YTDB console installation process is straightforward
and requires almost zero effort.

---

#### Use Case: A learning tool

*You are a new user of YouTrackDB and perhaps new to graphs.
You’re trying to get familiar with how Gremlin works and how it might fit into your project.
You want some "quick wins" with Gremlin and aim to conceptually
prove that the YouTrackDB stack is a good direction to go.*

It cannot be emphasized enough just how important the YTDB Console is to new users.
The interactive nature of a
[REPL](http://en.wikipedia.org/wiki/Read%E2%80%93eval%E2%80%93print_loop) makes it possible
to quickly try some Gremlin code and get some notion of success or failure without the longer
process of build tools (e.g. [Maven](https://maven.apache.org/)),
[IDEs](https://en.wikipedia.org/wiki/Integrated_development_environment),
compilation, and application execution.
The faster you can iterate through versions of your Gremlin code,
the faster you can advance your knowledge.

You can create an empty YouTrackDB Graph as follows:

```
gremlin> ytdb = YourTracks.instance("data")(1)
gremlin> ytdb.create("tg", DatabaseType.MEMORY, "superuser", "adminpwd", "admin")(2)
gremlin> graph = ytdb.openGraph("tg", "superuser", "adminpwd") (3)
==>YTDBGraph[tg]
gremlin> g = traversal().with(graph) (4)
==>graphtraversalsource[YTDBGraph[tg], standard]
```

1. Creates an instance of the manager for the YTDB databases.
   YouTrackDB supports both in-memory and disk databases.
   When creating an instance of the YTDB manager, you provide a root folder where all
   database instances will be contained.
2. Creates a new in-memory database with an admin user that has username “superuser” and password
   “amdindpwd”.
3. Creates the `YTDBGraph` instance that is the API to
   the [structure](https://tinkerpop.apache.org/docs/3.7.4/reference/#graph-structure) of the graph.
4. Creates the `TraversalSource` which is the API
   for [processing](https://tinkerpop.apache.org/docs/3.7.4/reference/#the-graph-process)
   or [traversing](https://tinkerpop.apache.org/docs/3.7.4/tutorials/getting-started/#_graph_traversal_staying_simple)
   that `YTDBGraph`.

*Note 1: The `traversal()` method is statically imported from the `AnonymousTraversalSource`
class so that it can be used in a more fluent fashion.*

*Note 2: In case of usage of the Docker image always use "data" as a root folder for the database
instance. The "data" folder is mounted to the "/opt/ytdb-console/data" folder of the container.
Later we plan to introduce possibility to set up default configuration for the YTDB by providing
`ytdb.properties` file in the classpath so you will not need to provide root folder manually.`*

Now that you have an empty YouTrackDB Graph instance, you could load a sample of your data
and get started with some traversals. Of course,
you might also try one of the "toy" graphs (i.e. graphs with sample data)
that YTDB packages with the console through the `YTDBDemoGraphFactory`.
`YTDBDemoGraphFactory` has a number of static methods that can be called to create these
standard `YTDBGraph` instances.
They are "standard" in the sense that they are typically used for all
TinkerPop examples and test cases.

- `createClassic()` - The original TinkerPop 2.x toy graph [diagram](images/tinkerpop-classic.png).
- `createModern()` - The TinkerPop 3.x representation of the "classic" graph,
  where the main difference is that vertex labels are defined and the "weight"
  edge property is a `double` rather than a`float` [diagram](images/tinkerpop-modern.png).

```
gremlin> ytdb = YourTracks.instance("data")
gremlin> graph = YTDBDemoGraphFactory.createModern(ytdb)
==>YTDBGraph[modern]
gremlin> g = traversal().with(graph)
==>graphtraversalsource[YTDBGraph[modern], standard]
```

As you might have noticed from the diagrams of these graphs,
these toy graphs are small (only a few vertices and edges each).
It is nice to have a small graph when learning Gremlin
so that you can easily see if you are getting the results you expect.
Even though these graphs are "small", they are robust enough in structure
to try out many different kinds of traversals.
However, if you find that a larger graph might be helpful,
there is another option: The Grateful Dead [schema](/images/grateful-dead-schema.png).

```
gremlin> ytdb = YourTracks.instance("data")
gremlin> graph = YTDBDemoGraphFactory.createGratefulDead(ytdb)
==>YTDBGraph[grateful-dead]
gremlin> g = traversal().with(graph)
==>graphtraversalsource[YTDBGraph[grateful-dead], standard]
gremlin> g.V().count()
==>808
gremlin> g.E().count()
==>8049
```

*Tip:If you find yourself in a position where you need to ask a question on the
[Zulip cthat](https://youtrackdb.zulipchat.com) about a traversal that you are having trouble with
in your application, try to convert the gist of it to one of the toy graphs.
Taking this step will make it easier for advanced YTDB users to help you,
which should lead to a faster response time for your problem.
In addition, there is the added benefit that the message will be more relevant to other users,
as it is not written solely in the context of your domain.
If the sample data sets don’t properly demonstrate your issue,
then including a Gremlin script that can construct a small body of sample data
would be equally helpful.*

As you get familiar with the console, it is good to know what some of the basic commands are.
A "command" is not "Gremlin code", but something interpreted by the console to have special
meaning in terms of configuring how the console works or performing a particular function
outside the code itself.
These commands are itemized in
the [TinkerPop reference documentation](https://tinkerpop.apache.org/docs/3.7.4/reference/#_console_commands),
but they can also be accessed within the console itself with the `:help` command.

The YTDB Console can also provide you with code help via auto-complete functionality.
Use the `<TAB>` key to trigger a search of possible method names that might complete
what you’ve typed to that point.

If you stuck in the console, you can always start over using `:c` command.
This command clears the command buffer. Use `:cs` command to clear the screen.

---

#### Use Case: Application development

*You are an application developer, and the YouTrackDB stack will be central to your application
architecture.
You need to develop a series of services that will execute queries against a YTDB database
in support of the application front-end.*

Most application developers use an IDE, such
as [Intellij](https://en.wikipedia.org/wiki/IntelliJ_IDEA),
to help with their software development efforts. The IDE provides shortcuts and conveniences
that make complex engineering jobs more productive. When developing applications for YouTrackDB,
the YTDB Console should accompany the IDE as an additional tool to enhance productivity.
In other words, when you open your IDE, open the YTDB Console next to it.

You will find that as you write Gremlin for your code base in your IDE, you will inevitably
reach a point of sufficient complexity in your traversals where you will need to:

- Quickly test the traversal over real data to determine if it is correct.
- Test or debug pieces of the traversal in isolation.
- Experiment with different ways of expressing the same traversal.
- Examine the performance of a traversal through the
  [profile()](https://tinkerpop.apache.org/docs/3.7.4/reference/#profile-step)
  or[explain()](https://tinkerpop.apache.org/docs/3.7.4/reference/#explain-step) steps.

Consider an example where you are developing an application that uses YTDB and the data from
the "modern" toy graph. You want to encapsulate some logic for a graph traversal
that finds a "person" vertex, iterates outgoing edges and groups
the adjacent vertices as "value maps".

As you have read the documentation and have been experimenting with Gremlin for a while,
you head to your IDE with your open project in it and write a simple class like this:

```java
package com.my.company;

import org.apache.tinkerpop.gremlin.structure.Vertex;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;

import static org.apache.tinkerpop.gremlin.structure.T.*;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.*;

import java.util.List;
import java.util.Map;

public final class Traversals {

  public static Map<String, List<Vertex>> groupAround(YTDBGraphTraversalSource g, RID vertexId) {
    return g.V(vertexId).outE().
        group().
        by(label).
        by(inV()).next();
  }
}
```

*Note: We suggest using static importing, which allows for a more fluid code style.
If the static import above were removed in favor of a standard import of the `__` and `T` classes,
the traversal would read as follows: `g.V(id).outE().group().by(T.label).by(__.inV()).next()`.
The console automatically performs the static imports for these methods,
so they do not need to be imported again in that environment.*

![Diagram of modern graph](images/tinkerpop-modern.png)

The diagram above displays the "modern" graph for reference.
Assuming that g refers to a `TraversalSource` generated from a
`YTDBGraph` instance that refers to that graph, calling `groupAround` with the vertexId argument,
should return a Map with two keys: "knows" and "created",
where the "knows" key should have vertices "2" and "4" and the "created" key should have vertex "3".
You write your test, compile your application, and execute your test only to find it failing
on the "knows" key, which only has one vertex associated to it instead of two.

As you have the YTDB Console open, you decide to debug the problem there.
You copy your Gremlin code from the IDE and execute it in the console and confirm the failure:

```
gremlin> g.V().has("person", "name", "marko")
==>v[#23:1]
gremlin> g.V(RID.of("#23:1")).outE().group().by(label).by(inV().values("name")).next()
==>created=lop
==>knows=vadas
```

*Note 1: You may have different values of IDs of vertices in your console output.*
*Note 2: The `values()` step is used to extract the values of a property from a vertex.
We use it so our evaluations in the console were more readable and results were grouped by names of
vertices instead of their IDs.*

Structurally, this `Traversal` is sound, however, it makes an assumption about how `inV()` will
be utilized as an inner `Traversal`, in reality `inV()` only has `next()` called upon
it pulling a single vertex from the "knows" edges. You can remedy that by adding
`fold()` to `inV()` . `fold()` will return a `List` of all the vertices returned by `inv()`
step and will be used as a second argument to `group()` step.

```
gremlin> g.V(RID.of("#23:1")).outE().group().by(label).by(inV().values("name").fold()).next()
==>created=[lop]
==>knows=[josh, vadas]
```

You can now see that your result is as expected, and you can modify your Java class to reflect the
change:

```java
package com.my.company;

import org.apache.tinkerpop.gremlin.structure.Vertex;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.api.record.RID;

import static org.apache.tinkerpop.gremlin.structure.T.*;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.*;

import java.util.List;
import java.util.Map;

public final class Traversals {

  public static Map<String, List<Vertex>> groupAround(YTDBGraphTraversalSource g, RID vertexId) {
    return g.V(vertexId).outE().
        group().
        by(label).
        by(inV().fold()).next();
  }
}
```

It is important to remember that the console always iterates over the entire result set.
That leads to the most common "simple" bug that users encounter. It’s all too easy to write a
traversal as follows:

```
gremlin> g.V().has('name','marko').drop()
gremlin> g.V().has('name','marko').count()
==>0
```

As you can see, the first traversal removes vertices with the "name" field of "marko" and
the second traversal verifies that there are no vertices named "marko" after the first is executed.
After seeing success like that in the console, it is all too tempting to copy and paste that line of
code to a Java class like:

```java
package com.my.company;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;

public final class Traversals {

  public static void removeByName(YTDBGraphTraversalSource g, String name) {
    g.V().has("name", name).drop();
  }
}
```

Of course, this won’t work, and you will likely be left wondering why your unit test for
`removeByName` is failing.

Outside the console you must:

1. Iterate the whole results of the traversal to trigger its execution and process all vertices.
2. Commit transactions to persist the changes.

Though you can use `g.tx().commit()` to commit transactions, we recommend using
either `executeInTx()` or `computeInTx()` or `autoExecuteInTx()` methods of
`YTDBGraphTraversalSource` for automatic
transaction management.
Aa bonus if you use `autoExecuteInTx()` and return the result as `YTDBGraphTraversal`,
the traversal will be iterated over automatically.

After the changes, a new version of your code will look like:

```java
package com.my.company;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;

public final class Traversals {

  public static void removeByName(YTDBGraphTraversalSource g, String name) {
    g.autoExecuteInTx(() -> it.V().has("name", name).drop());
  }
}
```

