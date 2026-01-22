//file:noinspection GrPackage
ytdb = YourTracks.instance("databases")
g = YTDBDemoGraphFactory.createModern(ytdb)
g.V().has("person", "name", "marko")
g.V().has('name','marko').drop()
g.V().has('name','marko').count()
g.close()
ytdb.close()