package psymbolic.valuesummary;

import psymbolic.runtime.Message;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;

public class UnionVStype implements Serializable {
    private static HashMap<String, UnionVStype> allTypes = new HashMap<>();

    Class<? extends ValueSummary> typeClass;
    String[] names;

    public static UnionVStype getUnionVStype(Class<? extends ValueSummary> tc, String[] n) {
        UnionVStype result;

        String typeName = tc.toString();
        if (n != null) {
            typeName += String.format("[%s]", String.join(",", n));
        }

        if (!allTypes.containsKey(typeName)) {
            result = new UnionVStype(tc, n);
            allTypes.put(typeName, result);
        } else {
            result = allTypes.get(typeName);
        }

        return result;
    }

    private UnionVStype(Class<? extends ValueSummary> tc, String[] n) {
        typeClass = tc;
        names = n;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UnionVStype)) return false;
        UnionVStype rhs = (UnionVStype) o;
        if (names == null) {
            return (rhs.names == null) && typeClass.equals(rhs.typeClass);
        } else if (rhs.names == null) {
            return (names == null) && typeClass.equals(rhs.typeClass);
        } else {
            return typeClass.equals(rhs.typeClass) && (names.equals(rhs.names));
        }
    }

    @Override
    public int hashCode() {
        if (names == null) {
            return typeClass.hashCode();
        } else {
            return 31 * typeClass.hashCode() + names.hashCode();
        }
    }

}
