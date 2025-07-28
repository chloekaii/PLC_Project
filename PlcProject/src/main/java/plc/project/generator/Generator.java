package plc.project.generator;

import plc.project.analyzer.Ir;
import plc.project.analyzer.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public class Generator implements Ir.Visitor<StringBuilder, RuntimeException> {

    private final StringBuilder builder = new StringBuilder();
    private int indent = 0;

    private void newline(int indent) {
        builder.append("\n");
        builder.append("    ".repeat(indent));
    }

    @Override
    public StringBuilder visit(Ir.Source ir) {
        builder.append(Environment.imports()).append("\n\n");
        builder.append("public final class Main {").append("\n\n");
        builder.append(Environment.definitions()).append("\n");
        //Java doesn't allow for nested functions, but we will pretend it does.
        //To support simple programs involving functions, we will "hoist" any
        //variable/function declaration at the start of the program to allow
        //these functions to be used as valid Java.
        indent = 1;
        boolean main = false;
        for (var statement : ir.statements()) {
            newline(indent);
            if (!main) {
                if (statement instanceof Ir.Stmt.Let || statement instanceof Ir.Stmt.Def) {
                    builder.append("static ");
                } else {
                    builder.append("public static void main(String[] args) {");
                    main = true;
                    indent = 2;
                    newline(indent);
                }
            }
            visit(statement);
        }
        if (main) {
            builder.append("\n").append("    }");
        }
        indent = 0;
        builder.append("\n\n").append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Let ir) {
        if (ir.type() instanceof Type.Object) {
            builder.append("var ").append(ir.name());
        } else {
            builder.append(ir.type().jvmName()).append(" ").append(ir.name());
        }
        ir.value().ifPresent(v -> {
            builder.append(" = ");
            visit(v);
        });
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Def ir) {
        // <ReturnType> <name>(<First> <first>, <Second> <second>, <Third> <third>, ...) {
        //    <statements...>
        //}
        builder.append(ir.returns().jvmName());
        builder.append(" ");
        builder.append(ir.name());
        builder.append("(");
        for (int i = 0; i < ir.parameters().size(); i++) {
            builder.append(ir.parameters().get(i).type().jvmName());
            builder.append(" ");
            builder.append(ir.parameters().get(i).name());
            if (i < ir.parameters().size() - 1) {
                builder.append(", ");
            }
        }
        builder.append(") {");
        ++indent;
        for (int i = 0; i < ir.body().size(); i++) {
            newline(indent);
            visit(ir.body().get(i));
        }
        newline(--indent);
        builder.append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.If ir) {
        builder.append("if (");
        visit(ir.condition());
        builder.append(") {");
        ++indent;
        for (int i = 0; i < ir.thenBody().size(); i++) {
            if (i == 0) {
                newline(indent);
            }
            visit(ir.thenBody().get(i));
            if (i < ir.thenBody().size() - 1) {
                newline(indent);
            }
        }
        newline(--indent);
        builder.append("}");
        if (!ir.elseBody().isEmpty()) {
            builder.append(" else {");
            ++indent;
            for (int i = 0; i < ir.elseBody().size(); i++) {
                if (i == 0) {
                    newline(indent);
                }
                visit(ir.elseBody().get(i));
                if (i < ir.elseBody().size() - 1) {
                    newline(indent);
                }
            }
            newline(--indent);
            builder.append("}");
        }
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.For ir) {
        // for (<Type> <name> : <expression>) {
        //    <statements...> (separated by newlines)
        //}
        builder.append("for (");
        builder.append(ir.type().jvmName());
        builder.append(" ");
        builder.append(ir.name());
        builder.append(" : ");
        visit(ir.expression());
        builder.append(") {");
        ++indent;
        for (int i = 0; i < ir.body().size(); i++) {
            if (i == 0) {
                newline(indent);
            }
            visit(ir.body().get(i));
            if (i < ir.body().size() - 1) {
                newline(indent);
            }
        }
        newline(--indent);
        builder.append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Return ir) {
        builder.append("return ");
        if (ir.value().isPresent()) {
            visit(ir.value().get());
        } else {
            builder.append("null");
        }
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Expression ir) {
        visit(ir.expression());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Variable ir) {
        visit(ir.variable());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Property ir) {
        visit(ir.property());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Literal ir) {
        var literal = switch (ir.value()) {
            case null -> "null";
            case Boolean b -> b.toString();
            case BigInteger i -> "new BigInteger(\"" + i + "\")";
            case BigDecimal d -> "new BigDecimal(\"" + d + "\")";
            case String s -> "\"" + s + "\""; //TODO: Escape characters?
            //If the IR value isn't one of the above types, the Parser/Analyzer
            //is returning an incorrect IR - this is an implementation issue,
            //hence throw AssertionError rather than a "standard" exception.
            default -> throw new AssertionError(ir.value().getClass());
        };
        builder.append(literal);
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Group ir) {
        builder.append("(");
        visit(ir.expression());
        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Binary ir) {
        switch (ir.operator()) {
            case "+" -> {
                if (ir.type().equals(Type.INTEGER) || ir.type().equals(Type.DECIMAL)) {
                    builder.append("(");
                    visit(ir.left());
                    builder.append(").add(");
                    visit(ir.right());
                    builder.append(")");
                    return builder;
                }
                visit(ir.left());
                builder.append(" + ");
                visit(ir.right());
                return builder;
            }
            case "-" -> {
                builder.append("(");
                visit(ir.left());
                builder.append(").subtract(");
                visit(ir.right());
                builder.append(")");
                return builder;
            }
            case "*" -> {
                builder.append("(");
                visit(ir.left());
                builder.append(").multiply(");
                visit(ir.right());
                builder.append(")");
                return builder;
            }
            case "/" -> {
                if (ir.type().equals(Type.INTEGER)) {
                    builder.append("(");
                    visit(ir.left());
                    builder.append(").divide(");
                    visit(ir.right());
                    builder.append(")");
                    return builder;
                }
                builder.append("(");
                visit(ir.left());
                builder.append(").divide(");
                visit(ir.right());
                builder.append(", RoundingMode.HALF_EVEN)");
                return builder;
            }
            case "<", ">", "<=", ">=" -> {
                builder.append("(");
                visit(ir.left());
                builder.append(").compareTo(");
                visit(ir.right());
                builder.append(") ").append(ir.operator()).append(" 0");
                return builder;
            }
            case "==", "!=" -> {
                if (ir.operator().equals("!=")) {
                    builder.append("!");
                }
                builder.append("Objects.equals(");
                visit(ir.left());
                builder.append(", ");
                visit(ir.right());
                builder.append(")");
                return builder;
            }
            case "AND" -> {
                boolean leftIsOr = ir.left() instanceof Ir.Expr.Binary bin &&
                        bin.operator().equals("OR");
                if (leftIsOr) {
                    builder.append("(");
                }
                visit(ir.left());
                if (leftIsOr) {
                    builder.append(")");
                }
                builder.append(" && ");
                visit(ir.right());
                return builder;
            }
            case "OR" -> {
                visit(ir.left());
                builder.append(" || ");
                visit(ir.right());
                return builder;
            }
        }
        throw new RuntimeException("Something went wrong when building a binary expression.");
    }

    @Override
    public StringBuilder visit(Ir.Expr.Variable ir) {
        builder.append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Property ir) {
        visit(ir.receiver());
        builder.append(".");
        builder.append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Function ir) {
        builder.append(ir.name());
        builder.append("(");
        for (int i = 0; i < ir.arguments().size(); i++) {
            visit(ir.arguments().get(i));
            if (i < ir.arguments().size() - 1) {
                builder.append(", ");
            }
        }
        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Method ir) {
        visit(ir.receiver());
        builder.append(".");
        builder.append(ir.name());
        builder.append("(");
        for (int i = 0; i < ir.arguments().size(); i++) {
            visit(ir.arguments().get(i));
            if (i < ir.arguments().size() - 1) {
                builder.append(", ");
            }
        }
        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.ObjectExpr ir) {
        builder.append("new Object() {");
        ++indent;
        for (int i = 0; i < ir.fields().size(); i++) {
            if (i == 0) {
                newline(indent);
            }
            visit(ir.fields().get(i));
        }

        if (!ir.fields().isEmpty() && !ir.methods().isEmpty()) {
            newline(indent);
        }

        for (int i = 0; i < ir.methods().size(); i++) {
            if (i == 0) {
                newline(indent);
            }
            visit(ir.methods().get(i));
        }
        newline(--indent);
        builder.append("}");
        return builder;
    }

}
