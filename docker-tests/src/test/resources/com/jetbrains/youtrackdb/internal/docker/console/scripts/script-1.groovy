//file:noinspection GrPackage
ytdb = YourTracks.instance("databases")
ytdb.create("tg", DatabaseType.MEMORY, "superuser", "adminpwd", "admin")
g = ytdb.openTraversal("tg", "superuser", "adminpwd")
g.close()
ytdb.close()