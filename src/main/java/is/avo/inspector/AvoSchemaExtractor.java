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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Schema extraction per SPEC.md §9. Property order follows the input's iteration order. */
public class AvoSchemaExtractor {

	// SPEC.md §9.3.2: beyond this depth a nested value is reported as an empty object.
	static final int MAX_DEPTH = 10;
	// Complex values expanded per extractSchema call, as in Node. Shared references that are not
	// cycles could otherwise expand exponentially; past the budget a complex value is reported as
	// "object", like the depth cap.
	static final int MAX_EXPANSIONS = 10_000;
	// Property entries emitted per extractSchema call, at every depth and in iteration order (the
	// cross-SDK extraction bound): a map with a million keys would otherwise produce a body of tens
	// of megabytes. Entries past it are omitted. Independent of MAX_EXPANSIONS.
	static final int MAX_PROPERTIES = 10_000;

	// The state of one extractSchema call: the containers on the path to the current value (by
	// identity), the complex values expanded so far and the property entries emitted so far.
	private static final class Walk {
		final Set<Object> ancestors = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
		int expansions;
		int properties;

		// Counts one property entry; false once the budget is spent, so the entry is omitted.
		boolean takeProperty() {
			if (properties >= MAX_PROPERTIES) {
				return false;
			}
			properties++;
			return true;
		}

		Walk(Object root) {
			ancestors.add(root);
			expansions = 1;
		}
	}

	@NotNull Map<String, AvoEventSchemaType> extractSchema(@Nullable Object eventProperties, boolean shouldLogIfEnabled) {
		Map<String, AvoEventSchemaType> result;

		if (eventProperties == null || eventProperties == JSONObject.NULL) {
			result = new LinkedHashMap<>();
		} else if (eventProperties instanceof Map || eventProperties instanceof JSONObject) {
			result = mapObject(eventProperties, 0, new Walk(eventProperties));
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

		Walk walk = new Walk(eventProperties);
		for (Field eventPropertyField: eventPropertiesFields) {
			if (!walk.takeProperty()) {
				break;
			}
			AvoEventSchemaType propertyType = getAvoSchemaType(eventProperties, eventPropertyField, walk);
			result.put(eventPropertyField.getName(), propertyType);
		}
		return result;
	}

	private AvoEventSchemaType getAvoSchemaType(Object eventProperties, Field eventPropertyField, Walk walk) {
		try {
			return objectToAvoType(eventPropertyField.get(eventProperties), 0, walk);
		} catch (IllegalAccessException ignored) {
			return new AvoEventSchemaType.AvoUnknownType();
		}
	}

	// The object branch of mapping(): one entry per own property, in iteration order. Each entry is
	// counted before its value is mapped, so a parent precedes its children in the budget.
	private Map<String, AvoEventSchemaType> mapObject(@NotNull Object object, int depth, Walk walk) {
		Map<String, AvoEventSchemaType> result = new LinkedHashMap<>();

		if (object instanceof JSONObject) {
			JSONObject json = (JSONObject) object;
			for (Iterator<String> it = json.keys(); it.hasNext() && walk.takeProperty(); ) {
				String key = it.next();
				result.put(key, objectToAvoType(json.opt(key), depth, walk));
			}
		} else {
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
				if (!walk.takeProperty()) {
					break;
				}
				result.put(String.valueOf(entry.getKey()), objectToAvoType(entry.getValue(), depth, walk));
			}
		}

		return result;
	}

	// The type of one property value, descending into objects and lists.
	// A complex value is a leaf, reported as "object", when it is at the depth cap, is its own
	// ancestor (a cycle), or the call's expansion budget is spent. Otherwise mapping it counts one
	// expansion.
	private AvoEventSchemaType objectToAvoType(@Nullable Object val, int depth, Walk walk) {
		AvoEventSchemaType type = specType(val, depth, walk);
		type.legacyOverride = legacyOverride(val);
		return type;
	}

	private AvoEventSchemaType specType(@Nullable Object val, int depth, Walk walk) {
		if (isComplex(val) && (depth >= MAX_DEPTH || walk.ancestors.contains(val) || walk.expansions >= MAX_EXPANSIONS)) {
			return new AvoEventSchemaType.AvoTruncatedObject();
		}
		if (isComplex(val)) {
			walk.expansions++;
		}

		if (isList(val)) {
			walk.ancestors.add(val);
			try {
				return mapList(val, depth + 1, walk);
			} finally {
				walk.ancestors.remove(val);
			}
		}

		if (isObject(val)) {
			walk.ancestors.add(val);
			try {
				return new AvoEventSchemaType.AvoObject(mapObject(val, depth + 1, walk));
			} finally {
				walk.ancestors.remove(val);
			}
		}

		return scalarType(val);
	}

	// The 1.1.1 name (behind toString/equals/hashCode) of a value whose name 1.1.1 did not derive
	// from its elements or properties, or null when the name follows from the extracted type.
	// 1.1.1 named typed arrays by their class alone and did not recognise the other types here.
	@Nullable
	static String legacyOverride(@Nullable Object val) {
		if (val == null) {
			return null;
		}
		if (val instanceof BigInteger || val instanceof BigDecimal || val instanceof AtomicInteger
				|| val instanceof AtomicLong || val instanceof JSONObject
				|| (val instanceof Collection && !(val instanceof List))) {
			return "unknown";
		}
		if (!val.getClass().isArray()) {
			return null;
		}
		String className = val.getClass().getName();
		switch (className) {
			case "[Ljava.lang.String;":
				return AvoEventSchemaType.AvoList.legacyListName(Arrays.asList("string", "null"));
			case "[Ljava.lang.Integer;":
				return AvoEventSchemaType.AvoList.legacyListName(Arrays.asList("int", "null"));
			case "[I":
				return AvoEventSchemaType.AvoList.legacyListName(Collections.singletonList("int"));
			case "[Ljava.lang.Boolean;":
				return AvoEventSchemaType.AvoList.legacyListName(Arrays.asList("boolean", "null"));
			case "[Z":
				return AvoEventSchemaType.AvoList.legacyListName(Collections.singletonList("boolean"));
			case "[Ljava.lang.Float;":
			case "[Ljava.lang.Double;":
				return AvoEventSchemaType.AvoList.legacyListName(Arrays.asList("float", "null"));
			case "[D":
			case "[F":
				return AvoEventSchemaType.AvoList.legacyListName(Collections.singletonList("float"));
			default:
				if (className.startsWith("[L") && className.contains("List")) {
					return AvoEventSchemaType.AvoList.legacyListName(Arrays.asList("list<>", "null"));
				} else if (className.startsWith("[L")) {
					// 1.1.1 typed these as a list of an empty object, whose name was "".
					return AvoEventSchemaType.AvoList.legacyListName(Arrays.asList("", "null"));
				}
				return "unknown";
		}
	}

	// The array branch of mapping(), in one pass over the elements: the list type comes from the
	// first element, each element is mapped, primitive types are deduplicated by value. Objects and
	// nested lists are never merged (reference identity in the JS reference parser).
	private AvoEventSchemaType.AvoList mapList(@NotNull Object list, int depth, Walk walk) {
		Class<?> component = list.getClass().getComponentType();
		if (component != null && component.isPrimitive()) {
			// Every element has the component's type, so there is nothing to walk or box.
			if (Array.getLength(list) == 0) {
				return new AvoEventSchemaType.AvoList("string", new ArrayList<AvoEventSchemaType>());
			}
			AvoEventSchemaType type = primitiveType(component);
			List<AvoEventSchemaType> children = new ArrayList<>(1);
			children.add(type);
			return new AvoEventSchemaType.AvoList(type.getReportedName(), children);
		}

		Iterable<?> elements = list instanceof Object[] ? Arrays.asList((Object[]) list) : (Iterable<?>) list;
		String elementType = "string";
		boolean first = true;
		List<AvoEventSchemaType> children = new ArrayList<>();
		Set<String> seenPrimitives = new HashSet<>();
		// The 1.1.1 union (toString/equals) dedups by 1.1.1 name, which can differ from the wire
		// name: BigInteger is "int" on the wire but was "unknown".
		List<AvoEventSchemaType> legacyElements = new ArrayList<>();
		Set<String> seenLegacyPrimitives = new HashSet<>();

		for (Object element : elements) {
			if (first) {
				elementType = isNull(element) ? "string" : basicType(element);
				first = false;
			}
			AvoEventSchemaType mapped = objectToAvoType(element, depth, walk);
			boolean nonPrimitive = (mapped instanceof AvoEventSchemaType.AvoObject && !(mapped instanceof AvoEventSchemaType.AvoTruncatedObject))
					|| mapped instanceof AvoEventSchemaType.AvoList;
			if (nonPrimitive || seenPrimitives.add(mapped.getReportedName())) {
				children.add(mapped);
			}
			if (nonPrimitive || seenLegacyPrimitives.add(mapped.legacyName())) {
				legacyElements.add(mapped);
			}
		}

		// "list(unknown)" is not a wire type (SPEC.md §7.3.4): an unrecognised element is an object.
		if ("unknown".equals(elementType)) {
			elementType = "object";
		}
		return new AvoEventSchemaType.AvoList(elementType, children, legacyElements);
	}

	private static AvoEventSchemaType primitiveType(Class<?> primitive) {
		if (primitive == boolean.class) {
			return new AvoEventSchemaType.AvoBoolean();
		} else if (primitive == float.class || primitive == double.class) {
			return new AvoEventSchemaType.AvoFloat();
		} else if (primitive == char.class) {
			return new AvoEventSchemaType.AvoString();
		}
		return new AvoEventSchemaType.AvoInt();
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

	private static boolean isList(@Nullable Object val) {
		return val instanceof Collection || val instanceof JSONArray || (val != null && val.getClass().isArray());
	}

	private static boolean isComplex(@Nullable Object val) {
		return isObject(val) || isList(val);
	}
}
