package plc.project.analyzer;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * IMPORTANT: DO NOT CHANGE! This file is part of our project's API and should
 * not be modified by your solution.
 */
public sealed interface Type {

    Primitive NIL = new Primitive("Nil", "Void");
    Primitive BOOLEAN = new Primitive("Boolean", "boolean");
    Primitive INTEGER = new Primitive("Integer", "BigInteger");
    Primitive DECIMAL = new Primitive("Decimal", "BigDecimal");
    Primitive STRING = new Primitive("String", "String");

    Primitive ANY = new Primitive("Any", "Object");
    Primitive EQUATABLE = new Primitive("Equatable", "Object");
    Primitive COMPARABLE = new Primitive("Comparable", "Comparable");
    Primitive ITERABLE = new Primitive("Iterable", "Iterable<BigInteger>");

    record Primitive(
        String name,
        String jvmName
    ) implements Type {}

    record Function(
        List<Type> parameters,
        Type returns
    ) implements Type {}

    record Object(
        Scope scope
    ) implements Type {

        @Override
        public boolean equals(java.lang.Object obj) {
            return obj instanceof Object other
                && scope.collect(true).equals(other.scope.collect(true));
        }

        @Override
        public String toString() {
            return "Object[fields=" + scope.collect(true) + "]";
        }

    }

    default String jvmName() {
        return switch (this) {
            case Primitive primitive -> primitive.jvmName;
            case Function function -> switch (function.parameters.size()) {
                case 0 -> "Supplier<" + function.returns.jvmName() + ">";
                case 1 -> "Function<" + function.parameters.getFirst() + ", " + function.returns.jvmName() + ">";
                case 2 -> "BiFunction<" + function.parameters.getFirst() + ", " + function.parameters.getLast() + ", " + function.returns.jvmName() + ">";
                default -> "Object";
            };
            case Object _ -> "var"; //from variable type inference
        };
    }

}
