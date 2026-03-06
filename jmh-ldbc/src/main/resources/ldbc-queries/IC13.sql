/* IC13: Single shortest path.
   Find the shortest path between two Persons via KNOWS edges. */
SELECT shortestPath(
  (SELECT FROM Person WHERE id = :person1Id),
  (SELECT FROM Person WHERE id = :person2Id),
  'BOTH', 'KNOWS'
).size() - 1 as pathLength
