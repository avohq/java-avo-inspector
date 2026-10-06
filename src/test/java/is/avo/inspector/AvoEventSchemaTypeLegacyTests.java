package is.avo.inspector;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

// toString/equals/hashCode keep their 1.1.1 results. Every expected value below was produced by
// the 1.1.1 code (origin/main) for the same input.
public class AvoEventSchemaTypeLegacyTests {

    private static Map<String, Object> m(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    private static AvoEventSchemaType type(Object value) {
        return new AvoSchemaExtractor().extractSchema(m("v", value), false).get("v");
    }

    private static void assertSame111(Object a, Object b) {
        assertEquals(type(a), type(b));
        assertEquals(type(a).hashCode(), type(b).hashCode());
    }

    @Test
    public void scalarNames() {
        assertEquals("int", type(1).toString());
        assertEquals("int", type(2L).toString());
        assertEquals("float", type(1.5).toString());
        assertEquals("float", type(0.0f).toString());
        assertEquals("boolean", type(true).toString());
        assertEquals("string", type("x").toString());
        assertEquals("string", type('c').toString());
        assertEquals("null", type(null).toString());
        assertEquals("null", type(JSONObject.NULL).toString());
        assertEquals("unknown", type(new Object()).toString());
    }

    @Test
    public void listNamesAreTheUnionOfElementTypes() {
        assertEquals("list<>", type(new ArrayList<>()).toString());
        assertEquals("list<string>", type(Arrays.asList("a", "b")).toString());
        assertEquals("list<string|int>", type(Arrays.asList(1, "a")).toString());
        assertEquals("list<string|int>", type(Arrays.asList("a", 1)).toString());
        assertEquals("list<int>", type(Arrays.asList(1, 2)).toString());
        assertEquals("list<list<int>>", type(Arrays.asList(Arrays.asList(1), Arrays.asList(2))).toString());
        assertEquals("list<{\"propertyName\":\"a\",\"propertyType\":\"int\"}>", type(Arrays.asList(m("a", 1))).toString());
        assertEquals("list<null|int>", type(Arrays.asList(null, 1)).toString());
        assertEquals("list<string|int>", type(new JSONArray().put(1).put("a")).toString());
    }

    @Test
    public void objectNamesAreTheirPropertiesAsJson() {
        assertEquals("", type(m()).toString());
        assertEquals("{\"propertyName\":\"a\",\"propertyType\":\"int\"}", type(m("a", 1)).toString());
        assertEquals("{\"propertyName\":\"a\",\"propertyType\":\"int\"},{\"propertyName\":\"b\",\"propertyType\":\"string\"}",
                type(m("a", 1, "b", "x")).toString());
        assertEquals("{\"propertyName\":\"o\",\"children\":[{\"propertyName\":\"p\",\"propertyType\":\"list<int>\"}],\"propertyType\":\"object\"}",
                type(m("o", m("p", Arrays.asList(1)))).toString());
        assertEquals("{\"propertyName\":\"l\",\"propertyType\":\"list<string|int>\"}", type(m("l", Arrays.asList(1, "a"))).toString());
    }

    @Test
    public void equalityFollowsTheName() {
        assertSame111(1, 2L);
        assertNotEquals(type(1), type(1.0));

        assertNotEquals(type(m("a", 1)), type(m("a", "x")));
        assertNotEquals(type(m("a", 1)), type(m("b", 1)));
        assertNotEquals(type(m()), type(m("a", 1)));

        assertSame111(Arrays.asList(1), Arrays.asList(1, 2));
        assertSame111(Arrays.asList(1, "a"), Arrays.asList("a", 1));
        assertNotEquals(type(Arrays.asList(1)), type(Arrays.asList(1, "a")));
        assertNotEquals(type(Arrays.asList(Arrays.asList(1))), type(Arrays.asList(Arrays.asList("a"))));
        assertNotEquals(type(Arrays.asList(m("a", 1))), type(Arrays.asList(m("a", "x"))));
        assertNotEquals(type(new ArrayList<>()), type(Arrays.asList("a")));
    }

    @Test
    public void wireNamesAreUnaffected() {
        AvoEventSchemaType list = type(Arrays.asList(1, "a"));
        assertEquals("list(int)", list.getReportedName());
        assertEquals("object", type(m("a", 1)).getReportedName());
    }

    @Test
    public void arrayNamesFollow111() {
        assertEquals("list<string|null>", type(new String[]{"a"}).toString());
        assertEquals("list<string|null>", type(new String[0]).toString());
        assertEquals("list<null|int>", type(new Integer[]{1}).toString());
        assertEquals("list<int>", type(new int[]{1}).toString());
        assertEquals("list<int>", type(new int[0]).toString());
        assertEquals("list<boolean|null>", type(new Boolean[]{true}).toString());
        assertEquals("list<boolean>", type(new boolean[]{true}).toString());
        assertEquals("list<null|float>", type(new Float[]{1f}).toString());
        assertEquals("list<null|float>", type(new Double[]{1.0}).toString());
        assertEquals("list<float>", type(new double[]{1}).toString());
        assertEquals("list<float>", type(new float[0]).toString());
        assertEquals("unknown", type(new byte[]{1}).toString());
        assertEquals("unknown", type(new long[]{1}).toString());
        assertEquals("unknown", type(new short[]{1}).toString());
        assertEquals("unknown", type(new char[]{'a'}).toString());
        assertEquals("list<|null>", type(new Long[]{1L}).toString());
        assertEquals("list<null|list<>>", type(new ArrayList[]{new ArrayList<Object>()}).toString());
        assertEquals("list<|null>", type(new Object[]{1}).toString());
        assertEquals("unknown", type(new int[][]{{1}}).toString());
    }

    @Test
    public void typesThat111DidNotRecogniseAreUnknown() {
        JSONObject nestedJson = new JSONObject();
        nestedJson.put("a", 1);
        assertEquals("unknown", type(new java.util.HashSet<Object>(Arrays.asList("a"))).toString());
        assertEquals("unknown", type(new java.math.BigDecimal("1.5")).toString());
        assertEquals("unknown", type(java.math.BigInteger.ONE).toString());
        assertEquals("unknown", type(new java.util.concurrent.atomic.AtomicInteger(1)).toString());
        assertEquals("unknown", type(new java.util.concurrent.atomic.AtomicLong(1)).toString());
        assertEquals("unknown", type(nestedJson).toString());
        assertEquals("list<unknown>", type(Arrays.asList(nestedJson)).toString());
        assertEquals("list<unknown>", type(new JSONArray().put(nestedJson)).toString());
        assertEquals("list<unknown>", type(Arrays.asList(new java.math.BigDecimal("1"))).toString());
        assertEquals("list<int|unknown>", type(Arrays.asList(new java.util.HashSet<Object>(), 1)).toString());
    }

    @Test
    public void nestedNamesFollow111() {
        assertEquals("list<list<string|null>>", type(Arrays.asList((Object) new String[]{"a"})).toString());
        assertEquals("{\"propertyName\":\"s\",\"propertyType\":\"unknown\"}",
                type(m("s", new java.util.HashSet<Object>(Arrays.asList(1)))).toString());
        assertEquals("{\"propertyName\":\"s\",\"propertyType\":\"list<string|null>\"},{\"propertyName\":\"i\",\"propertyType\":\"list<int>\"}",
                type(m("i", new int[0], "s", new String[0])).toString());
        assertEquals("{\"propertyName\":\"b\",\"propertyType\":\"unknown\"}", type(m("b", java.math.BigInteger.TEN)).toString());
    }

    @Test
    public void arrayAndUnknownEqualityFollows111() {
        assertNotEquals(type(new String[]{"a"}), type(Arrays.asList("a")));
        assertSame111(new int[0], new int[]{1});
        assertSame111(new byte[]{1}, new Object() { });
        assertSame111(new java.math.BigDecimal("1"), java.math.BigInteger.ONE);
    }

    @Test
    public void wireTypesStayOnTheSpec() {
        assertEquals("list(string)", type(new String[]{"a"}).getReportedName());
        assertEquals("list(int)", type(new byte[]{1}).getReportedName());
        assertEquals("float", type(new java.math.BigDecimal("1.5")).getReportedName());
        assertEquals("object", type(new JSONObject().put("a", 1)).getReportedName());
    }

    @Test
    public void listUnionComesFromTheElementsNotTheWireDedup() {
        // On the wire BigInteger is an int and BigDecimal a float, so the wire dedup merges them;
        // 1.1.1 named them unknown.
        assertEquals("list<int|unknown>", type(Arrays.asList(1, java.math.BigInteger.ONE)).toString());
        assertEquals("list<float|unknown>", type(Arrays.asList(1.5, java.math.BigDecimal.ONE)).toString());
        assertEquals("list<int|unknown>", type(Arrays.asList(java.math.BigInteger.ONE, 1)).toString());
        assertEquals("list<int|unknown>", type(Arrays.asList(1, new java.util.concurrent.atomic.AtomicInteger(1), 2L)).toString());
        assertEquals("list(int)", type(Arrays.asList(1, java.math.BigInteger.ONE)).getReportedName());
    }
}
