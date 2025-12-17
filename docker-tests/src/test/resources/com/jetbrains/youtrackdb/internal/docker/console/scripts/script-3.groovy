//file:noinspection GrPackage
ytdb = YourTracks.instance("databases")
g = YTDBDemoGraphFactory.createGratefulDead(ytdb)
g.V().count()
g.E().count()
g.close()
ytdb.close()