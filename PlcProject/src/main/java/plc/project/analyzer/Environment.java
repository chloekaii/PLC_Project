package plc.project.analyzer;

import java.util.List;
import java.util.Map;

/**
 * IMPORTANT: DO NOT CHANGE! This file is part of our project's API and should
 * not be modified by your solution.
 */
public final class Environment {

    public static final Map<String, Type> TYPES = Map.of(
        "Nil", Type.NIL,
        "Boolean", Type.BOOLEAN,
        "Integer", Type.INTEGER,
        "Decimal", Type.DECIMAL,
        "String", Type.STRING,

        "Any", Type.ANY,
        "Equatable", Type.EQUATABLE,
        "Comparable", Type.COMPARABLE,
        "Iterable", Type.ITERABLE
    );

    public static Scope scope() {
        var scope = new Scope(null);
        //"Native" functions for printing and creating lists.
        //"log" and "list" have been removed, since our type system can't represent them (why?)
        scope.define("debug", new Type.Function(List.of(Type.ANY), Type.NIL));
        scope.define("print", new Type.Function(List.of(Type.ANY), Type.NIL));
        scope.define("range", new Type.Function(List.of(Type.INTEGER, Type.INTEGER), Type.ITERABLE));
        //Helper functions for testing variables, functions, and objects.
        scope.define("variable", Type.STRING);
        scope.define("function", new Type.Function(List.of(), Type.NIL));
        scope.define("functionAny", new Type.Function(List.of(Type.ANY), Type.ANY));
        scope.define("functionString", new Type.Function(List.of(Type.STRING), Type.STRING));
        var object = new Type.Object(new Scope(null));
        scope.define("object", object);
        object.scope().define("property", Type.STRING);
        object.scope().define("method", new Type.Function(List.of(), Type.NIL));
        object.scope().define("methodAny", new Type.Function(List.of(Type.ANY), Type.ANY));
        object.scope().define("methodString", new Type.Function(List.of(Type.STRING), Type.STRING));
        //Helper variables for testing non-literal types;
        scope.define("any", Type.ANY);
        scope.define("equatable", Type.EQUATABLE);
        scope.define("comparable", Type.COMPARABLE);
        scope.define("iterable", Type.ITERABLE);
        return scope;
    }

}
