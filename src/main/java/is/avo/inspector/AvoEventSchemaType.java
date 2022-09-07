package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

@SuppressWarnings("WeakerAccess")
public abstract class AvoEventSchemaType {

    @NotNull
    abstract String getReportedName();

    @NotNull protected String getReadableName() {
        return getReportedName();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof AvoEventSchemaType) {
            return getReportedName().equals(((AvoEventSchemaType) obj).getReportedName());
        }

        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return getReportedName().hashCode();
    }

    @NotNull
    @Override
    public String toString() {
        return getReportedName();
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
        @NotNull Set<AvoEventSchemaType> subtypes;

        AvoList(@NotNull Set<AvoEventSchemaType> subtypes) {
            this.subtypes = subtypes;
        }

        @NotNull
        @Override
        String getReportedName() {
            StringBuilder types = new StringBuilder();

            boolean first = true;
            for (AvoEventSchemaType subtype: subtypes) {
                if (!first) {
                    types.append("|");
                }

                types.append(subtype.getReportedName());
                first = false;
            }

            return "list<" + types + ">";
        }

        @NotNull
        @Override
        protected String getReadableName() {
            StringBuilder types = new StringBuilder();

            boolean first = true;
            for (AvoEventSchemaType subtype: subtypes) {
                if (!first) {
                    types.append("|");
                }

                types.append(subtype.getReadableName());
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
            String jsonArrayString = Util.remapProperties(children).toString();
            return jsonArrayString.substring(1, jsonArrayString.length() - 1);
        }

        @NotNull
        @Override
        protected String getReadableName() {
            String jsonArrayString = Util.readableJsonProperties(children);
            return jsonArrayString;
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
