package plc.project.lexer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

public final class LexerTests {

    @ParameterizedTest
    @MethodSource
    void testWhitespace(String test, String input, boolean success) {
        test(input, List.of(), success);
    }

    public static Stream<Arguments> testWhitespace() {
        return Stream.of(
            Arguments.of("Space", " ", true),
            Arguments.of("Newline", "\n", true),
            Arguments.of("Multiple", "   \n   ", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testComment(String test, String input, boolean success) {
        test(input, List.of(), success);
    }

    public static Stream<Arguments> testComment() {
        return Stream.of(
            Arguments.of("Comment", "//comment", true),
            Arguments.of("Multiple", "//first\n//second", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testIdentifier(String test, String input, boolean success) {
        test(input, List.of(new Token(Token.Type.IDENTIFIER, input)), success);
    }

    public static Stream<Arguments> testIdentifier() {
        return Stream.of(
            Arguments.of("Alphabetic", "getName", true),
            Arguments.of("Alphanumeric", "thelegend27", true),
            Arguments.of("Leading Hyphen", "-five", false),
            Arguments.of("Leading Digit", "1fish2fish", false),
            Arguments.of("Underscore", "_varName", true),
            Arguments.of("Mixed Case", "camelCase123", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testInteger(String test, String input, boolean success) {
        test(input, List.of(new Token(Token.Type.INTEGER, input)), success);
    }

    public static Stream<Arguments> testInteger() {
        return Stream.of(
            Arguments.of("Single Digit", "1", true),
            Arguments.of("Multiple Digits", "123", true),
            Arguments.of("Exponent", "1e10", true),
            Arguments.of("Missing Exponent Digits", "1e", false),
            Arguments.of("Negative Number", "-42", true),
            Arguments.of("Leading Zero", "0123", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testDecimal(String test, String input, boolean success) {
        test(input, List.of(new Token(Token.Type.DECIMAL, input)), success);
    }

    public static Stream<Arguments> testDecimal() {
        return Stream.of(
            Arguments.of("Integer", "1", false),
            Arguments.of("Multiple Digits", "123.456", true),
            Arguments.of("Exponent", "1.0e10", true),
            Arguments.of("Trailing Decimal", "1.", false),
            Arguments.of("Leading Decimal", ".5", false),
            Arguments.of("Negative Decimal", "-3.14", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testCharacter(String test, String input, boolean success) {
        test(input, List.of(new Token(Token.Type.CHARACTER, input)), success);
    }

    public static Stream<Arguments> testCharacter() {
        return Stream.of(
            Arguments.of("Alphabetic", "\'c\'", true),
            Arguments.of("Newline Escape", "\'\\n\'", true),
            Arguments.of("Unterminated", "\'u", false),
            Arguments.of("Multiple", "\'abc\'", false),
            Arguments.of("Backspace Escape", "'\\b'", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testString(String test, String input, boolean success) {
        test(input, List.of(new Token(Token.Type.STRING, input)), success);
    }

    public static Stream<Arguments> testString() {
        return Stream.of(
            Arguments.of("Empty", "\"\"", true),
            Arguments.of("Alphabetic", "\"string\"", true),
            Arguments.of("Newline Escape", "\"Hello,\\nWorld\"", true),
            Arguments.of("Invalid Escape", "\"invalid\\escape\"", false),
            Arguments.of("Mixed Escapes", "\"Hello,\\tWorld\\n\"", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testOperator(String test, String input, boolean success) {
        test(input, List.of(new Token(Token.Type.OPERATOR, input)), success);
    }

    public static Stream<Arguments> testOperator() {
        return Stream.of(
            Arguments.of("Character", "(", true),
            Arguments.of("Comparison", "<=", true)
        );
    }

    @ParameterizedTest
    @MethodSource
    void testInteraction(String test, String input, List<Token> expected) {
        test(input, expected, true);
    }

    public static Stream<Arguments> testInteraction() {
        return Stream.of(
            Arguments.of("Whitespace", "first second", List.of(
                new Token(Token.Type.IDENTIFIER, "first"),
                new Token(Token.Type.IDENTIFIER, "second")
            )),
            Arguments.of("Identifier Leading Hyphen", "-five", List.of(
                new Token(Token.Type.OPERATOR, "-"),
                new Token(Token.Type.IDENTIFIER, "five")
            )),
            Arguments.of("Identifier Leading Digit", "1fish2fish", List.of(
                new Token(Token.Type.INTEGER, "1"),
                new Token(Token.Type.IDENTIFIER, "fish2fish")
            )),
            Arguments.of("Integer Missing Exponent Digits", "1e", List.of(
                new Token(Token.Type.INTEGER, "1"),
                new Token(Token.Type.IDENTIFIER, "e")
            )),
            Arguments.of("Decimal Missing Decimal Digits", "1.", List.of(
                new Token(Token.Type.INTEGER, "1"),
                new Token(Token.Type.OPERATOR, ".")
            )),
            Arguments.of("Operator Multiple Operators", "<=>", List.of(
                new Token(Token.Type.OPERATOR, "<="),
                new Token(Token.Type.OPERATOR, ">")
            )),
            Arguments.of("Comment With Code", "// comment \n x = 5", List.of(
                    new Token(Token.Type.IDENTIFIER, "x"),
                    new Token(Token.Type.OPERATOR, "="),
                    new Token(Token.Type.INTEGER, "5")
            ))
        );
    }

    @ParameterizedTest
    @MethodSource
    void testException(String test, String input) {
        Assertions.assertThrows(LexException.class, () -> new Lexer(input).lex());
    }

    public static Stream<Arguments> testException() {
        return Stream.of(
            Arguments.of("Character Unterminated", "\'u"),
            Arguments.of("Character Multiple", "\'abc\'"),
            Arguments.of("String Invalid Escape", "\"invalid\\escape\"")
        );
    }

    @ParameterizedTest
    @MethodSource
    void testProgram(String test, String input, List<Token> expected) {
        test(input, expected, true);
    }

    public static Stream<Arguments> testProgram() {
        return Stream.of(
            Arguments.of("Variable", "LET x = 5;", List.of(
                new Token(Token.Type.IDENTIFIER, "LET"),
                new Token(Token.Type.IDENTIFIER, "x"),
                new Token(Token.Type.OPERATOR, "="),
                new Token(Token.Type.INTEGER, "5"),
                new Token(Token.Type.OPERATOR, ";")
            )),
            Arguments.of("Print Function", "print(\"Hello, World!\");", List.of(
                new Token(Token.Type.IDENTIFIER, "print"),
                new Token(Token.Type.OPERATOR, "("),
                new Token(Token.Type.STRING, "\"Hello, World!\""),
                new Token(Token.Type.OPERATOR, ")"),
                new Token(Token.Type.OPERATOR, ";")
            )),
            Arguments.of("Looks like expression", "1-5", List.of(
                    new Token(Token.Type.INTEGER, "1"),
                    new Token(Token.Type.INTEGER, "-5")
            )),
            Arguments.of("Is actual expression", "1 - 5", List.of(
                    new Token(Token.Type.INTEGER, "1"),
                    new Token(Token.Type.OPERATOR, "-"),
                    new Token(Token.Type.INTEGER, "5")
            )),
            Arguments.of("Comment with Code", "//comment\nLET x = 5;", List.of(
                    new Token(Token.Type.IDENTIFIER, "LET"),
                    new Token(Token.Type.IDENTIFIER, "x"),
                    new Token(Token.Type.OPERATOR, "="),
                    new Token(Token.Type.INTEGER, "5"),
                    new Token(Token.Type.OPERATOR, ";")
            )),
            Arguments.of("Complex Expression", "(x + y) * 3 - 2 / z;", List.of(
                    new Token(Token.Type.OPERATOR, "("),
                    new Token(Token.Type.IDENTIFIER, "x"),
                    new Token(Token.Type.OPERATOR, "+"),
                    new Token(Token.Type.IDENTIFIER, "y"),
                    new Token(Token.Type.OPERATOR, ")"),
                    new Token(Token.Type.OPERATOR, "*"),
                    new Token(Token.Type.INTEGER, "3"),
                    new Token(Token.Type.OPERATOR, "-"),
                    new Token(Token.Type.INTEGER, "2"),
                    new Token(Token.Type.OPERATOR, "/"),
                    new Token(Token.Type.IDENTIFIER, "z"),
                    new Token(Token.Type.OPERATOR, ";")
            )),
            Arguments.of("String Concatenation", "str1 + \" and \" + str2;", List.of(
                    new Token(Token.Type.IDENTIFIER, "str1"),
                    new Token(Token.Type.OPERATOR, "+"),
                    new Token(Token.Type.STRING, "\" and \""),
                    new Token(Token.Type.OPERATOR, "+"),
                    new Token(Token.Type.IDENTIFIER, "str2"),
                    new Token(Token.Type.OPERATOR, ";")
            )),
            Arguments.of("Nested Function Call", "foo(bar(42));", List.of(
                    new Token(Token.Type.IDENTIFIER, "foo"),
                    new Token(Token.Type.OPERATOR, "("),
                    new Token(Token.Type.IDENTIFIER, "bar"),
                    new Token(Token.Type.OPERATOR, "("),
                    new Token(Token.Type.INTEGER, "42"),
                    new Token(Token.Type.OPERATOR, ")"),
                    new Token(Token.Type.OPERATOR, ")"),
                    new Token(Token.Type.OPERATOR, ";")
            ))
    );
    }

    private static void test(String input, List<Token> expected, boolean success) {
        if (success) {
            var tokens = Assertions.assertDoesNotThrow(() -> new Lexer(input).lex());
            Assertions.assertEquals(expected, tokens);
        } else {
            //Consider both different results or exceptions to be acceptable.
            //This is a bit lenient, but makes adding tests much easier.
            try {
                Assertions.assertNotEquals(expected, new Lexer(input).lex());
            } catch (LexException ignored) {}
        }
    }

}
