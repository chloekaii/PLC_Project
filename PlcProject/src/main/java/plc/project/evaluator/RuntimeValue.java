package plc.project.evaluator;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public sealed interface RuntimeValue {

    record Primitive(
        @Nullable Object value
    ) implements RuntimeValue {}

    record Function(
        String name,
        Definition definition
    ) implements RuntimeValue {

        @FunctionalInterface
        interface Definition {
            RuntimeValue invoke(List<RuntimeValue> arguments) throws EvaluateException;
        }

        @Override
        public boolean equals(Object obj) {
            //Not strictly accurate, but sufficient for our needs - definitions are identity based.
            return obj instanceof Function function && name.equals(function.name);
        }

    }

    //Using "ObjectValue" to avoid confusion with Java's "Object"
    record ObjectValue(
        Optional<String> name,
        Scope scope
    ) implements RuntimeValue {

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ObjectValue object &&
                name.equals(object.name) &&
                scope.collect(true).equals(object.scope.collect(true));
        }

    }

    default String print() {
        switch (this) {
            case Primitive primitive: {
                if (primitive.value == null) {
                    return "NIL";
                } else if (primitive.value instanceof Boolean) {
                    return primitive.value.toString().toUpperCase();
                } else {
                    return primitive.value.toString();
                }
            }
            case Function function: {
                return "DEF " + function.name + "(?) DO ? END";
            }
            case ObjectValue object: {
                var builder = new StringBuilder();
                builder.append("Object");
                object.name.ifPresent(n -> builder.append("(").append(n).append(")"));
                builder.append(" { ");
                for (var field : object.scope.collect(true).entrySet()) {
                    builder.append(field.getKey()).append(" = `").append(field.getValue().print()).append("`, ");
                }
                builder.delete(builder.length() - 2, builder.length()); //delete trailing comma
                builder.append(" }");
                return builder.toString();
            }
        }
    }

}
