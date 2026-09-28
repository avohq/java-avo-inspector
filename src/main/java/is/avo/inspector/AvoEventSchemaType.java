package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("WeakerAccess")
public abstract class AvoEventSchemaType {

    /**
     * The wire {@code propertyType} (SPEC.md §7.3.4), e.g. {@code "int"}, {@code "object"} or
     * {@code "list(string)"}.
     */
    @NotNull
    abstract String getReportedName();

    /**
     * The value this type contributes when it is an element of a list's {@code children}
     * (SPEC.md §7.3.4): a type string for scalars, an array for objects and nested lists.
     */
    @NotNull
    Object toListChild() {
        return getReportedName();
    }

    @NotNull protected String getReadableName() {
        return getReportedName();
    }

    /**
     * The 1.1.1 name, e.g. {@code list<string|int>}, that {@link #toString()}, {@link #equals} and
     * {@link #hashCode()} keep using. The wire never uses it.
     */
    @NotNull
    String legacyName() {
        return getReportedName();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof AvoEventSchemaType) {
            return legacyName().equals(((AvoEventSchemaType) obj).legacyName());
        }

        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return legacyName().hashCode();
    }

    @NotNull
    @Override
    public String toString() {
        return legacyName();
    }

    public static class AvoInt extends AvoEventSchemaType {
        @NotNull
        @Override
        String getReportedName() {
            return "int";
        }
    }

    public static class AvoFloat extends AvoEventSchemaType {
        @NotNull
        @Override
        String getReportedName() {
            return "float";
        }
    }

    public static class AvoBoolean extends AvoEventSchemaType {
        @NotNull
        @Override
        String getReportedName() {
            return "boolean";
        }
    }

    public static class AvoString extends AvoEventSchemaType {
        @NotNull
        @Override
        String getReportedName() {
            return "string";
        }
    }

    public static class AvoNull extends AvoEventSchemaType {
        @NotNull
        @Override
        String getReportedName() {
            return "null";
        }
    }

    public static class AvoList extends AvoEventSchemaType {
        // Basic type of the first element, "string" for an empty list (SPEC.md §9.2).
        @NotNull final String elementType;
        // Mapped elements in order, primitive types deduplicated (SPEC.md §9.3.3).
        @NotNull final List<AvoEventSchemaType> children;

        AvoList(@NotNull String elementType, @NotNull List<AvoEventSchemaType> children) {
            this.elementType = elementType;
            this.children = children;
        }

        @NotNull
        @Override
        String getReportedName() {
            return "list(" + elementType + ")";
        }

        @NotNull
        JSONArray childrenToWire() {
            JSONArray result = new JSONArray();
            for (AvoEventSchemaType child : children) {
                result.put(child.toListChild());
            }
            return result;
        }

        @NotNull
        @Override
        Object toListChild() {
            return childrenToWire();
        }

        @NotNull
        @Override
        protected String getReadableName() {
            return getReportedName() + childrenToWire();
        }

        // The union of element types, in HashSet order, as 1.1.1 kept them.
        @NotNull
        @Override
        String legacyName() {
            Set<String> subtypes = new HashSet<>();
            for (AvoEventSchemaType child : children) {
                subtypes.add(child.legacyName());
            }

            StringBuilder types = new StringBuilder();
            boolean first = true;
            for (String subtype : subtypes) {
                if (!first) {
                    types.append("|");
                }
                types.append(subtype);
                first = false;
            }

            return "list<" + types + ">";
        }
    }

    public static class AvoObject extends AvoEventSchemaType {

        @NotNull Map<String, AvoEventSchemaType> children;

        AvoObject(@NotNull Map<String, AvoEventSchemaType> children) {
            this.children = children;
        }

        @NotNull
        @Override
        String getReportedName() {
            return "object";
        }

        @NotNull
        @Override
        Object toListChild() {
            return Util.remapProperties(children);
        }

        @NotNull
        @Override
        protected String getReadableName() {
            return Util.readableJsonProperties(children);
        }

        @NotNull
        @Override
        String legacyName() {
            String jsonArrayString = Util.legacyRemapProperties(children).toString();
            return jsonArrayString.substring(1, jsonArrayString.length() - 1);
        }
    }

    // A value past the depth cap (SPEC.md §9.3.2): an object with no children as a property, the
    // type string "object" as a list element.
    static final class AvoTruncatedObject extends AvoObject {

        AvoTruncatedObject() {
            super(new java.util.LinkedHashMap<String, AvoEventSchemaType>());
        }

        @NotNull
        @Override
        Object toListChild() {
            return getReportedName();
        }
    }

    public static class AvoUnknownType extends AvoEventSchemaType {

        @NotNull
        @Override
        String getReportedName() {
            return "unknown";
        }
    }
}
