package plc.project.lexer;

public record Token(
    Type type,
    String literal
) {

    public enum Type {
        IDENTIFIER,
        INTEGER,
        DECIMAL,
        CHARACTER,
        STRING,
        OPERATOR
    }

}
