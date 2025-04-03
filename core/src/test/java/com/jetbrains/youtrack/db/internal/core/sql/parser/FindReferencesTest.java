package com.jetbrains.youtrack.db.internal.core.sql.parser;

public class FindReferencesTest extends ParserTestAbstract {

  public void testSyntax() {
    checkRightSyntax("FIND REFERENCES #12:0");
    checkRightSyntax("find references #12:0");
    checkRightSyntax("FIND REFERENCES #12:0 [Person]");
    checkRightSyntax("FIND REFERENCES #12:0 [Person, Animal]");
    checkRightSyntax("FIND REFERENCES #12:0 [Person, collection:animal]");
    checkRightSyntax("FIND REFERENCES (select from foo where name = ?)");
    checkRightSyntax("FIND REFERENCES (select from foo where name = ?) [Person, collection:animal]");
    checkWrongSyntax("FIND REFERENCES ");
    checkWrongSyntax("FIND REFERENCES #12:0 #12:1");
    checkWrongSyntax("FIND REFERENCES #12:0, #12:1");
    checkWrongSyntax("FIND REFERENCES [#12:0, #12:1]");
    checkWrongSyntax("FIND REFERENCES foo");
  }
}
