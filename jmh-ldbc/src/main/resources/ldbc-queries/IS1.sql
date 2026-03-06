MATCH {class: Person, as: p, where: (id = :personId)}
  .out('IS_LOCATED_IN'){as: city}
RETURN p.firstName as firstName, p.lastName as lastName,
  p.birthday as birthday, p.locationIP as locationIP,
  p.browserUsed as browserUsed, city.id as cityId,
  p.gender as gender, p.creationDate as creationDate
