Please migrate the rest of the tests of test maven module
from TestNG to JUnit 4 one by one. Part of them already migrated.
Please note that we already have com.jetbrains.youtrackdb.internal.JUnitTestListener
equivalent to com.jetbrains.youtrackdb.TestNGTestListener.
Do not replace existing classes, instead create new package where new tests will be placed.
Migrate not all tests at once but one by one.
Add comment to the test class and add comment to the method of each test and write path to the
location of the original test method and test class.
Please note that order of execution of test classes should stay exactly the same as one class
can generate data in database and schema for database for another class.
Because of that, ensure test order on code level using JUnit API, that is important step.
Take it also into account during rewriting of the tests and running
the test as they may not succeed running isolated from each other.
After migration of each class run all JUnit tests (not one as tests can depend on from each other)
to ensure that migration of the class is done correctly. If some tests are failed,
fix them, and only after that continue to next class. After fixing of failed tests
of each test class, run all the existing
JUnit tests (not one as tests can depend on from each other) to ensure that they were not broken.
If you fail to attempt to fix the issue in the test, compare it with original test,
understand the steps of the test that is needed to be implemented and write the same
steps but using JUnit API. Do not forget to add a comment to the test method that
contains a reference to the original test for later comparison. After successful migration
of each class, run all JUnit tests from tests Maven module
(not one as they can depend on one from each other) to ensure that everything works as needed.
At the end of migration run all tests to ensure that nothing is broken.
Once migration is done, compare code of TestNG tests and JUnit tests and ensure that migration
is done correctly. Do not delete previous tests.