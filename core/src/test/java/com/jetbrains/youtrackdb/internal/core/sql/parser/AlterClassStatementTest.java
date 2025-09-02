package com.jetbrains.youtrackdb.internal.core.sql.parser;

import org.junit.Test;

public class AlterClassStatementTest extends ParserTestAbstract {

  @Test
  public void testPlain() {
    checkRightSyntax("ALTER CLASS Foo NAME Bar");
    checkRightSyntax("alter class Foo name Bar");
    checkRightSyntax("ALTER CLASS Foo NAME Bar UNSAFE");
    checkRightSyntax("alter class Foo name Bar unsafe");

    checkRightSyntax("ALTER CLASS `Foo bar` NAME `Bar bar`");

    checkRightSyntax("ALTER CLASS Foo DESCRIPTION bar");
    checkRightSyntax("ALTER CLASS Foo description bar");

    checkRightSyntax("ALTER CLASS Foo SUPERCLASSES Bar");
    checkRightSyntax("ALTER CLASS Foo superclasses Bar");

    checkRightSyntax("ALTER CLASS Foo SUPERCLASSES Bar, Bazz, braz");
    checkRightSyntax("ALTER CLASS Foo SUPERCLASSES Bar,Bazz,braz");
    checkRightSyntax("ALTER CLASS Foo SUPERCLASSES null");

    checkRightSyntax("ALTER CLASS Foo STRICT_MODE true");
    checkRightSyntax("ALTER CLASS Foo strict_mode true");
    checkRightSyntax("ALTER CLASS Foo STRICT_MODE false");

    checkRightSyntax("ALTER CLASS Foo CUSTOM bar=baz");
    checkRightSyntax("ALTER CLASS Foo custom bar=baz");
    checkRightSyntax("ALTER CLASS Foo CUSTOM bar = baz");

    checkRightSyntax("ALTER CLASS Person CUSTOM `onCreate.identityType`=role");

    checkWrongSyntax("ALTER CLASS Foo NAME Bar baz");

    checkWrongSyntax("ALTER CLASS Foo SUPERCLASS *Bar");
  }
}
