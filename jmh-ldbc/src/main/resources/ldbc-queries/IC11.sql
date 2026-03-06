MATCH {class: Person, as: start, where: (id = :personId)}
  .out('KNOWS'){while: ($depth < 2), as: person,
    where: (@rid <> $matched.start.@rid)}
  .outE('WORK_AT'){as: workEdge, where: (workFrom < :workFromYear)}
  .inV(){as: company}
  .out('IS_LOCATED_IN'){as: country, where: (name = :countryName)}
RETURN DISTINCT person.id as personId, person.firstName as firstName,
  person.lastName as lastName,
  company.name as organizationName, workEdge.workFrom as organizationWorkFromYear
ORDER BY organizationWorkFromYear ASC, personId ASC, organizationName DESC
LIMIT :limit
