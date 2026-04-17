-- LDBC SNB schema definition for YouTrackDB
-- Vertex classes, edge classes, properties, and indexes

-- Vertex classes
CREATE CLASS Place EXTENDS V;
CREATE PROPERTY Place.id LONG;
CREATE PROPERTY Place.name STRING;
CREATE PROPERTY Place.url STRING;
CREATE PROPERTY Place.type STRING;

CREATE CLASS Organisation EXTENDS V;
CREATE PROPERTY Organisation.id LONG;
CREATE PROPERTY Organisation.type STRING;
CREATE PROPERTY Organisation.name STRING;
CREATE PROPERTY Organisation.url STRING;

CREATE CLASS Company EXTENDS Organisation;
CREATE CLASS University EXTENDS Organisation;

CREATE CLASS TagClass EXTENDS V;
CREATE PROPERTY TagClass.id LONG;
CREATE PROPERTY TagClass.name STRING;
CREATE PROPERTY TagClass.url STRING;

CREATE CLASS Tag EXTENDS V;
CREATE PROPERTY Tag.id LONG;
CREATE PROPERTY Tag.name STRING;
CREATE PROPERTY Tag.url STRING;

CREATE CLASS Person EXTENDS V;
CREATE PROPERTY Person.id LONG;
CREATE PROPERTY Person.firstName STRING;
CREATE PROPERTY Person.lastName STRING;
CREATE PROPERTY Person.gender STRING;
CREATE PROPERTY Person.birthday DATETIME;
CREATE PROPERTY Person.creationDate DATETIME;
CREATE PROPERTY Person.locationIP STRING;
CREATE PROPERTY Person.browserUsed STRING;
CREATE PROPERTY Person.languages EMBEDDEDLIST STRING;
CREATE PROPERTY Person.emails EMBEDDEDLIST STRING;

CREATE CLASS Forum EXTENDS V;
CREATE PROPERTY Forum.id LONG;
CREATE PROPERTY Forum.title STRING;
CREATE PROPERTY Forum.creationDate DATETIME;

CREATE CLASS Message EXTENDS V;
CREATE PROPERTY Message.id LONG;
CREATE PROPERTY Message.creationDate DATETIME;
CREATE PROPERTY Message.locationIP STRING;
CREATE PROPERTY Message.browserUsed STRING;
CREATE PROPERTY Message.content STRING;
CREATE PROPERTY Message.length INTEGER;

CREATE CLASS Post EXTENDS Message;
CREATE PROPERTY Post.imageFile STRING;
CREATE PROPERTY Post.language STRING;

CREATE CLASS Comment EXTENDS Message;

-- Edge classes
-- LINK declarations on in/out properties enable index-assisted filtering:
-- the MATCH planner infers the target vertex class from the edge schema,
-- allowing index lookups and adjacency list intersection without explicit
-- @class filters in the query.

CREATE CLASS KNOWS EXTENDS E;
CREATE PROPERTY KNOWS.out LINK Person;
CREATE PROPERTY KNOWS.in LINK Person;
CREATE PROPERTY KNOWS.creationDate DATETIME;

CREATE CLASS IS_LOCATED_IN EXTENDS E;
-- out: Person, Organisation, Post, Comment (no single common superclass below V)
-- in: Place
CREATE PROPERTY IS_LOCATED_IN.in LINK Place;

CREATE CLASS HAS_INTEREST EXTENDS E;
CREATE PROPERTY HAS_INTEREST.out LINK Person;
CREATE PROPERTY HAS_INTEREST.in LINK Tag;

CREATE CLASS STUDY_AT EXTENDS E;
CREATE PROPERTY STUDY_AT.out LINK Person;
CREATE PROPERTY STUDY_AT.in LINK Organisation;
CREATE PROPERTY STUDY_AT.classYear INTEGER;

CREATE CLASS WORK_AT EXTENDS E;
CREATE PROPERTY WORK_AT.out LINK Person;
CREATE PROPERTY WORK_AT.in LINK Organisation;
CREATE PROPERTY WORK_AT.workFrom INTEGER;

CREATE CLASS HAS_MODERATOR EXTENDS E;
CREATE PROPERTY HAS_MODERATOR.out LINK Forum;
CREATE PROPERTY HAS_MODERATOR.in LINK Person;

CREATE CLASS HAS_MEMBER EXTENDS E;
CREATE PROPERTY HAS_MEMBER.out LINK Forum;
CREATE PROPERTY HAS_MEMBER.in LINK Person;
CREATE PROPERTY HAS_MEMBER.joinDate DATETIME;

CREATE CLASS CONTAINER_OF EXTENDS E;
CREATE PROPERTY CONTAINER_OF.out LINK Forum;
CREATE PROPERTY CONTAINER_OF.in LINK Message;

CREATE CLASS HAS_TAG EXTENDS E;
-- out: Forum, Post, Comment (no single common superclass below V)
CREATE PROPERTY HAS_TAG.in LINK Tag;

CREATE CLASS HAS_CREATOR EXTENDS E;
CREATE PROPERTY HAS_CREATOR.out LINK Message;
CREATE PROPERTY HAS_CREATOR.in LINK Person;

CREATE CLASS LIKES EXTENDS E;
CREATE PROPERTY LIKES.out LINK Person;
CREATE PROPERTY LIKES.in LINK Message;
CREATE PROPERTY LIKES.creationDate DATETIME;

CREATE CLASS REPLY_OF EXTENDS E;
CREATE PROPERTY REPLY_OF.out LINK Comment;
CREATE PROPERTY REPLY_OF.in LINK Message;

CREATE CLASS IS_PART_OF EXTENDS E;
CREATE PROPERTY IS_PART_OF.out LINK Place;
CREATE PROPERTY IS_PART_OF.in LINK Place;

CREATE CLASS IS_SUBCLASS_OF EXTENDS E;
CREATE PROPERTY IS_SUBCLASS_OF.out LINK TagClass;
CREATE PROPERTY IS_SUBCLASS_OF.in LINK TagClass;

CREATE CLASS HAS_TYPE EXTENDS E;
CREATE PROPERTY HAS_TYPE.out LINK Tag;
CREATE PROPERTY HAS_TYPE.in LINK TagClass;

-- Indexes
CREATE INDEX Place.id ON Place(id) UNIQUE;
CREATE INDEX Organisation.id ON Organisation(id) UNIQUE;
CREATE INDEX TagClass.id ON TagClass(id) UNIQUE;
CREATE INDEX Tag.id ON Tag(id) UNIQUE;
CREATE INDEX Person.id ON Person(id) UNIQUE;
CREATE INDEX Forum.id ON Forum(id) UNIQUE;
CREATE INDEX Message.id ON Message(id) UNIQUE;

CREATE INDEX Place.name ON Place(name) NOTUNIQUE;
CREATE INDEX Place.type ON Place(type) NOTUNIQUE;
CREATE INDEX Organisation.name ON Organisation(name) NOTUNIQUE;
CREATE INDEX Tag.name ON Tag(name) NOTUNIQUE;
CREATE INDEX TagClass.name ON TagClass(name) NOTUNIQUE;
CREATE INDEX Person.firstName ON Person(firstName) NOTUNIQUE;
CREATE INDEX Person.birthday ON Person(birthday) NOTUNIQUE;
CREATE INDEX Message.creationDate ON Message(creationDate) NOTUNIQUE;
CREATE INDEX Forum.creationDate ON Forum(creationDate) NOTUNIQUE;
CREATE INDEX HAS_MEMBER.joinDate ON HAS_MEMBER(joinDate) NOTUNIQUE;
CREATE INDEX WORK_AT.workFrom ON WORK_AT(workFrom) NOTUNIQUE;
-- KNOWS.creationDate enables index-assisted pre-filtering for bothE('KNOWS') queries
-- that filter by friendship date (e.g. "show all connections since date D").
-- Without this index, bothE traversals load all KNOWS link-bag entries and
-- filter post-load; with it, the planner uses PreFilterableChainedIterable to
-- intersect both link-bag directions against the index RID set before any disk I/O.
CREATE INDEX KNOWS.creationDate ON KNOWS(creationDate) NOTUNIQUE;
