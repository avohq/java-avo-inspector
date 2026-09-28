package is.avo.inspector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Schema extraction per SPEC.md §9. Property order follows the input's iteration order.
public class AvoSchemaExtractor {

	// SPEC.md §9.3.2: beyond this depth a nested value is reported as an empty object.
	static final int MAX_DEPTH = 10;

	@NotNull Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties, boolean shouldLogIfEnabled) {
		Map<String, AvoEventSchemaType> result;

		if (eventProperties == null || eventProperties == JSONObject.NULL) {
			result = new LinkedHashMap<>();
		} else if (eventProperties instanceof Map || eventProperties instanceof JSONObject) {
			result = mapObject(eventProperties, 0);
		} else {
			result = extractSchemaFromObject(eventProperties);
		}

		if (shouldLogIfEnabled && AvoInspector.isLogging()) {
			AvoInspector.logPostExtract(null, result);
		}

		return result;
	}

	private Map<String, AvoEventSchemaType> extractSchemaFromObject(@NotNull Object eventProperties) {
		Map<String, AvoEventSchemaType> result = new LinkedHashMap<>();

		List<Field> eventPropertiesFields = new ArrayList<>();

		for (Class<?> eventPropertiesClass = eventProperties.getClass();
		     eventPropertiesClass != Object.class && eventPropertiesClass != null;
		     eventPropertiesClass = eventPropertiesClass.getSuperclass())
		{
			Field[] fields = eventPropertiesClass.getDeclaredFields();
			eventPropertiesFields.addAll(Arrays.asList(fields));
		}

		for (Field eventPropertyField: eventPropertiesFields) {
			AvoEventSchemaType propertyType = getAvoSchemaType(eventProperties, eventPropertyField);
			result.put(eventPropertyField.getName(), propertyType);
		}
		return result;
	}

	private AvoEventSchemaType getAvoSchemaType(Object eventProperties, Field eventPropertyField) {
		try {
			return objectToAvoType(eventPropertyField.get(eventProperties), 0);
		} catch (IllegalAccessException ignored) {
			return new AvoEventSchemaType.AvoUnknownType();
		}
	}

	// The object branch of mapping(): one entry per own property, in iteration order.
	private Map<String, AvoEventSchemaType> mapObject(@NotNull Object object, int depth) {
		Map<String, AvoEventSchemaType> result = new LinkedHashMap<>();

		if (object instanceof JSONObject) {
			JSONObject json = (JSONObject) object;
			for (Iterator<String> it = json.keys(); it.hasNext(); ) {
				String key = it.next();
				result.put(key, objectToAvoType(json.opt(key), depth));
			}
		} else {
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
				result.put(String.valueOf(entry.getKey()), objectToAvoType(entry.getValue(), depth));
			}
		}

		return result;
	}

	// The type of one property value, descending into objects and lists.
	private AvoEventSchemaType objectToAvoType(@Nullable Object val, int depth) {
		if (isComplex(val) && depth >= MAX_DEPTH) {
			return new AvoEventSchemaType.AvoObject(new LinkedHashMap<String, AvoEventSchemaType>());
		}

		List<Object> elements = listElements(val);
		if (elements != null) {
			Object first = elements.isEmpty() ? null : elements.get(0);
			String elementType = isNull(first) ? "string" : basicType(first);
			return new AvoEventSchemaType.AvoList(elementType, mapList(elements, depth + 1));
		}

		if (isObject(val)) {
			return new AvoEventSchemaType.AvoObject(mapObject(val, depth + 1));
		}

		return scalarType(val);
	}

	// The array branch of mapping(): each element mapped, primitive types deduplicated by value.
	// Objects and nested lists are never merged (reference identity in the JS reference parser).
	private List<AvoEventSchemaType> mapList(@NotNull List<Object> elements, int depth) {
		List<AvoEventSchemaType> result = new ArrayList<>();
		Set<String> seenPrimitives = new HashSet<>();

		for (Object element : elements) {
			AvoEventSchemaType mapped = objectToAvoType(element, depth);
			if (mapped instanceof AvoEventSchemaType.AvoObject || mapped instanceof AvoEventSchemaType.AvoList) {
				result.add(mapped);
			} else if (seenPrimitives.add(mapped.getReportedName())) {
				result.add(mapped);
			}
		}

		return result;
	}

	// getBasicPropType(): a nested list counts as "object".
	private static String basicType(@NotNull Object val) {
		if (isComplex(val)) {
			return "object";
		}
		return scalarType(val).getReportedName();
	}

	private static AvoEventSchemaType scalarType(@Nullable Object val) {
		if (isNull(val)) {
			return new AvoEventSchemaType.AvoNull();
		} else if (val instanceof Integer || val instanceof Long || val instanceof Short || val instanceof Byte
				|| val instanceof BigInteger || val instanceof AtomicInteger || val instanceof AtomicLong) {
			return new AvoEventSchemaType.AvoInt();
		} else if (val instanceof Float || val instanceof Double || val instanceof BigDecimal) {
			return new AvoEventSchemaType.AvoFloat();
		} else if (val instanceof Boolean) {
			return new AvoEventSchemaType.AvoBoolean();
		} else if (val instanceof String || val instanceof Character) {
			return new AvoEventSchemaType.AvoString();
		} else {
			return new AvoEventSchemaType.AvoUnknownType();
		}
	}

	private static boolean isNull(@Nullable Object val) {
		return val == null || val == JSONObject.NULL || val instanceof AvoEventSchemaType.AvoNull;
	}

	private static boolean isObject(@Nullable Object val) {
		return val instanceof Map || val instanceof JSONObject;
	}

	private static boolean isComplex(@Nullable Object val) {
		return isObject(val) || listElements(val) != null;
	}

	@Nullable
	private static List<Object> listElements(@Nullable Object val) {
		if (val instanceof Collection) {
			return new ArrayList<Object>((Collection<?>) val);
		} else if (val instanceof JSONArray) {
			JSONArray jsonArray = (JSONArray) val;
			List<Object> result = new ArrayList<>(jsonArray.length());
			for (int i = 0; i < jsonArray.length(); i++) {
				result.add(jsonArray.opt(i));
			}
			return result;
		} else if (val != null && val.getClass().isArray()) {
			int length = Array.getLength(val);
			List<Object> result = new ArrayList<>(length);
			for (int i = 0; i < length; i++) {
				result.add(Array.get(val, i));
			}
			return result;
		}
		return null;
	}
}
