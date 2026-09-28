package is.avo.inspector;

import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

class Util {

    static String currentTimeAsISO8601UTCString() {
        SimpleDateFormat ISO8601UTC = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); // new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        ISO8601UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
        return ISO8601UTC.format(new Date());
    }

    // The wire eventProperties array (SPEC.md §7.3.4): children only on objects and lists.
    static JSONArray remapProperties(Map<String, AvoEventSchemaType> originalProperties) {
        JSONArray properties = new JSONArray();

        for (Map.Entry<String, AvoEventSchemaType> property : originalProperties.entrySet()) {
            AvoEventSchemaType propValue = property.getValue();
            if (propValue == null) {
                continue;
            }

            JSONObject prop = new JSONObject();
            prop.put("propertyName", property.getKey());
            prop.put("propertyType", propValue.getReportedName());
            if (propValue instanceof AvoEventSchemaType.AvoObject) {
                prop.put("children", remapProperties(((AvoEventSchemaType.AvoObject) propValue).children));
            } else if (propValue instanceof AvoEventSchemaType.AvoList) {
                prop.put("children", ((AvoEventSchemaType.AvoList) propValue).childrenToWire());
            }
            properties.put(prop);
        }

        return properties;
    }

    // The 1.1.1 property JSON behind AvoObject.legacyName(): HashMap property order and 1.1.1
    // type names. Not the wire format.
    static JSONArray legacyRemapProperties(Map<String, AvoEventSchemaType> originalProperties) {
        Map<String, AvoEventSchemaType> hashOrdered = new HashMap<>();
        for (Map.Entry<String, AvoEventSchemaType> property : originalProperties.entrySet()) {
            hashOrdered.put(property.getKey(), property.getValue());
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        for (String propKey : hashOrdered.keySet()) {
            AvoEventSchemaType propValue = hashOrdered.get(propKey);
            if (propValue == null) {
                continue;
            }

            Map<String, Object> prop = new HashMap<>();
            prop.put("propertyName", propKey);
            if (propValue instanceof AvoEventSchemaType.AvoObject && propValue.legacyOverride == null) {
                prop.put("propertyType", "object");
                prop.put("children", legacyRemapProperties(((AvoEventSchemaType.AvoObject) propValue).children));
            } else {
                prop.put("propertyType", propValue.legacyName());
            }
            properties.add(prop);
        }

        return new JSONArray(properties);
    }

    static String readableJsonProperties(Map<String, AvoEventSchemaType> originalProperties) {
        StringBuilder result = new StringBuilder("{");

        boolean first = true;
        for (Map.Entry<String, AvoEventSchemaType> property : originalProperties.entrySet()) {
            AvoEventSchemaType propType = property.getValue();
            if (!first) {
                result.append(", ");
            }
            result.append(JSONObject.quote(property.getKey())).append(": ")
                    .append(propType != null ? propType.getReadableName() : "null");
            first = false;
        }

        return result.append("}").toString();
    }

    public static Map<String, Object> jsonToMap(JSONObject json) {
        Map<String, Object> retMap = new HashMap<>();

        if(json != JSONObject.NULL) {
            retMap = toMap(json);
        }
        return retMap;
    }

    static Map<String, Object> toMap(JSONObject object) {
        Map<String, Object> map = new HashMap<>();

        Iterator<String> keysItr = object.keys();
        while(keysItr.hasNext()) {
            String key = keysItr.next();
            Object value = null;
            try {
                value = object.get(key);
            } catch (JSONException e) {
                continue;
            }

            if(value instanceof JSONArray) {
                value = toList((JSONArray) value);
            }

            else if(value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    static List<Object> toList(JSONArray array) {
        List<Object> list = new ArrayList<>();
        for(int i = 0; i < array.length(); i++) {
            Object value;
            try {
                value = array.get(i);
            } catch (JSONException e) {
                continue;
            }
            if(value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if(value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }

    static boolean mapsEqual(@Nullable Map<?, ?> params, @Nullable Map<?, ?> otherParams) {
        if (params == null || otherParams == null) {
            return otherParams == null && params == null;
        }

        if (params.keySet().containsAll(otherParams.keySet()) && otherParams.keySet().containsAll(params.keySet())) {
            for (Object paramName : params.keySet()) {
                Object paramValue = params.get(paramName);
                Object otherParamValue = otherParams.get(paramName);

                return objectsEqual(paramValue, otherParamValue);
            }

            return  true;
        }

        return false;
    }

    private static boolean objectsEqual(@Nullable Object paramValue, @Nullable Object otherParamValue) {
        if (paramValue == null) {
            return otherParamValue == null;
        }

        if (paramValue.getClass().isArray()) {
            return arraysEqual(paramValue, otherParamValue);
        }

        if (paramValue instanceof Collection && otherParamValue instanceof Collection) {
            for (Object paramValueCollectionItem : (Collection)paramValue) {
                if (!objectsEqual(paramValueCollectionItem, otherParamValue)) {
                    return false;
                }
            }
        } else if (paramValue instanceof Map && otherParamValue instanceof Map) {
            return mapsEqual((Map)paramValue, (Map)otherParamValue);
        } else {
            return paramValue.equals(otherParamValue);
        }
        return true;
    }

    private static boolean arraysEqual(@Nullable Object paramValue, @Nullable Object otherParamValue) {
        if (paramValue instanceof Object[] && otherParamValue instanceof Object[]) {
            return Arrays.equals((Object[]) paramValue, (Object[]) otherParamValue);
        } else if (paramValue instanceof boolean[] && otherParamValue instanceof boolean[]) {
            return Arrays.equals((boolean[]) paramValue, (boolean[]) otherParamValue);
        } else if (paramValue instanceof int[] && otherParamValue instanceof int[]) {
            return Arrays.equals((int[]) paramValue, (int[]) otherParamValue);
        } else if (paramValue instanceof byte[] && otherParamValue instanceof byte[]) {
            return Arrays.equals((byte[]) paramValue, (byte[]) otherParamValue);
        } else if (paramValue instanceof short[] && otherParamValue instanceof short[]) {
            return Arrays.equals((short[]) paramValue, (short[]) otherParamValue);
        } else if (paramValue instanceof char[] && otherParamValue instanceof char[]) {
            return Arrays.equals((char[]) paramValue, (char[]) otherParamValue);
        } else if (paramValue instanceof long[] && otherParamValue instanceof long[]) {
            return Arrays.equals((long[]) paramValue, (long[]) otherParamValue);
        } else if (paramValue instanceof float[] && otherParamValue instanceof float[]) {
            return Arrays.equals((float[]) paramValue, (float[]) otherParamValue);
        } else if (paramValue instanceof double[] && otherParamValue instanceof double[]) {
            return Arrays.equals((double[]) paramValue, (double[]) otherParamValue);
        }
        return false;
    }

    static final String INTERNAL_ERROR_MESSAGE = "Avo Inspector: something went wrong. Please report to support@avo.app.";

    // SPEC.md §4.2 / §7.5: an internal error is always logged; in dev it is rethrown to the caller.
    static void handleException(Throwable e, String envName) {
        System.err.println(INTERNAL_ERROR_MESSAGE + " " + e);
        if (AvoInspectorEnv.Dev.getName().equals(envName)) {
            throw new RuntimeException(INTERNAL_ERROR_MESSAGE, e);
        }
    }
}
