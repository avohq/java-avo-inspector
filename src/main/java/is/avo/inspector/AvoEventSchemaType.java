package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;

import java.util.List;
import java.util.Map;

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

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof AvoEventSchemaType) {
            return getReadableName().equals(((AvoEventSchemaType) obj).getReadableName());
        }

        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return getReadableName().hashCode();
    }

    @NotNull
    @Override
    public String toString() {
        return getReadableName();
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
    }

    public static class AvoUnknownType extends AvoEventSchemaType {

        @NotNull
        @Override
        String getReportedName() {
            return "unknown";
        }
    }
}
