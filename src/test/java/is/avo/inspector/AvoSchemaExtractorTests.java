package is.avo.inspector;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// SPEC.md §9 / §10: extraction from native Java values, compared on the wire shape.
public class AvoSchemaExtractorTests {

    private final AvoSchemaExtractor extractor = new AvoSchemaExtractor();

    private void assertWire(String expectedJson, Object eventProperties) {
        JSONArray actual = Util.remapProperties(extractor.extractSchema(eventProperties, false));
        JSONArray expected = new JSONArray(expectedJson);
        assertTrue("expected " + expected + "\n but was " + actual, expected.similar(actual));
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    @Test
    public void primitivesKeepInsertionOrder() {
        assertWire("[{propertyName:a,propertyType:boolean},{propertyName:b,propertyType:int},"
                        + "{propertyName:c,propertyType:string},{propertyName:d,propertyType:float}]",
                props("a", true, "b", 1, "c", "hello", "d", 3.14));
    }

    @Test
    public void manyKeysKeepInsertionOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        StringBuilder expected = new StringBuilder("[");
        for (int i = 30; i > 0; i--) {
            input.put("key" + i, i);
            expected.append(i == 30 ? "" : ",").append("{propertyName:key").append(i).append(",propertyType:int}");
        }
        assertWire(expected.append("]").toString(), input);
    }

    @Test
    public void floatZeroIsFloatAndIntZeroIsInt() {
        // SPEC.md §9.3.1: the declared type is authoritative in statically-typed languages.
        assertWire("[{propertyName:d,propertyType:float},{propertyName:f,propertyType:float},"
                        + "{propertyName:i,propertyType:int},{propertyName:l,propertyType:int}]",
                props("d", 0.0, "f", 0.0f, "i", 0, "l", 0L));
    }

    @Test
    public void emptyAndFalsyValues() {
        assertWire("[{propertyName:a,propertyType:boolean},{propertyName:b,propertyType:int},"
                        + "{propertyName:c,propertyType:string},{propertyName:e,propertyType:'null'},"
                        + "{propertyName:f,propertyType:object,children:[]},"
                        + "{propertyName:g,propertyType:'list(string)',children:[]}]",
                props("a", false, "b", 0, "c", "", "e", null, "f", new LinkedHashMap<>(), "g", new ArrayList<>()));
    }

    @Test
    public void nestedObject() {
        assertWire("[{propertyName:user,propertyType:object,children:["
                        + "{propertyName:name,propertyType:string},{propertyName:age,propertyType:int}]}]",
                props("user", props("name", "Alice", "age", 30)));
    }

    @Test
    public void listOfStrings() {
        assertWire("[{propertyName:tags,propertyType:'list(string)',children:[string]}]",
                props("tags", Arrays.asList("a", "b", "c")));
    }

    @Test
    public void heterogeneousListTakesTypeFromFirstElement() {
        assertWire("[{propertyName:mixed,propertyType:'list(float)',children:["
                        + "float,string,[{propertyName:three,propertyType:int}]]}]",
                props("mixed", Arrays.asList(1.2, "two", props("three", 3))));
    }

    @Test
    public void listWithNestedStructuresKeepsEveryNonPrimitiveElement() {
        assertWire("[{propertyName:prop7,propertyType:'list(string)',children:["
                        + "string,"
                        + "[{propertyName:'obj in list',propertyType:boolean},{propertyName:'int field',propertyType:int}],"
                        + "[string],[int]]}]",
                props("prop7", Arrays.asList("a", "list", props("obj in list", true, "int field", 1),
                        Arrays.asList("another", "list"), Arrays.asList(1, 2))));
    }

    @Test
    public void listChildrenDeduplicatePrimitiveTypes() {
        assertWire("[{propertyName:vals,propertyType:'list(string)',children:[string,boolean,int,float]}]",
                props("vals", Arrays.asList("true", "false", true, 10, "true", true, 11, 10, 0.1, 0.1)));
    }

    @Test
    public void nestedListsAreDeduplicatedByValueInOrder() {
        // [[1], [2], ["x"], [1, "x"], ["x", 1]]: equal children once, compared in order.
        assertWire("[{propertyName:v,propertyType:'list(object)',children:[[int],[string],[int,string],[string,int]]}]",
                props("v", Arrays.asList(Collections.singletonList(1), Collections.singletonList(2),
                        Collections.singletonList("x"), Arrays.asList(1, "x"), Arrays.asList("x", 1))));
    }

    @Test
    public void objectChildrenAreDeduplicatedRegardlessOfPropertyOrder() {
        // The first occurrence is kept with its own property order; a property of another type, a
        // different nested value or a missing property is a different schema.
        assertWire("[{propertyName:v,propertyType:'list(object)',children:["
                        + "[{propertyName:b,propertyType:string},{propertyName:a,propertyType:int}],"
                        + "[{propertyName:a,propertyType:string},{propertyName:b,propertyType:string}],"
                        + "[{propertyName:a,propertyType:int}],"
                        + "[{propertyName:o,propertyType:object,children:[{propertyName:x,propertyType:int},{propertyName:y,propertyType:int}]}],"
                        + "[{propertyName:o,propertyType:object,children:[{propertyName:x,propertyType:float}]}]]}]",
                props("v", Arrays.asList(
                        props("b", "x", "a", 1), props("a", 2, "b", "y"), props("a", "s", "b", "t"), props("a", 3),
                        props("o", props("x", 1, "y", 2)), props("o", props("y", 3, "x", 4)),
                        props("o", props("x", 1.5)))));
    }

    @Test
    public void anEmptyObjectAndAnEmptyListAreTheSameChild() {
        // Both are the empty array on the wire.
        assertWire("[{propertyName:v,propertyType:'list(object)',children:[[]]}]",
                props("v", Arrays.asList(props(), Collections.emptyList())));
    }

    @Test
    public void javaArraysAreLists() {
        assertWire("[{propertyName:ints,propertyType:'list(int)',children:[int]},"
                        + "{propertyName:doubles,propertyType:'list(float)',children:[float]},"
                        + "{propertyName:strings,propertyType:'list(string)',children:[string]},"
                        + "{propertyName:empty,propertyType:'list(string)',children:[]}]",
                props("ints", new int[]{1, 2}, "doubles", new double[]{0.0}, "strings", new String[]{"a", "b"},
                        "empty", new boolean[0]));
    }

    @Test
    public void emptyNumericArraysAreTypedByTheirComponent() {
        // An empty boolean[] or char[] stays an empty list(string).
        assertWire("[{propertyName:d,propertyType:'list(float)',children:[float]},"
                        + "{propertyName:f,propertyType:'list(float)',children:[float]},"
                        + "{propertyName:b,propertyType:'list(int)',children:[int]},"
                        + "{propertyName:s,propertyType:'list(int)',children:[int]},"
                        + "{propertyName:i,propertyType:'list(int)',children:[int]},"
                        + "{propertyName:l,propertyType:'list(int)',children:[int]},"
                        + "{propertyName:z,propertyType:'list(string)',children:[]},"
                        + "{propertyName:c,propertyType:'list(string)',children:[]},"
                        + "{propertyName:zz,propertyType:'list(boolean)',children:[boolean]},"
                        + "{propertyName:cc,propertyType:'list(string)',children:[string]}]",
                props("d", new double[0], "f", new float[0], "b", new byte[0], "s", new short[0],
                        "i", new int[0], "l", new long[0], "z", new boolean[0], "c", new char[0],
                        "zz", new boolean[]{true}, "cc", new char[]{'a'}));
    }

    @Test(timeout = 10_000)
    public void nearlyTenThousandObjectChildrenAreDeduplicatedInOnePass() {
        // One hash lookup per element, no pairwise comparison. 9,990 maps stay inside both budgets.
        List<Object> distinct = new ArrayList<>();
        for (int i = 0; i < 9_990; i++) {
            distinct.add(props("k" + i, i));
        }
        JSONArray wire = Util.remapProperties(extractor.extractSchema(props("distinct", distinct), false));
        assertEquals(9_990, wire.getJSONObject(0).getJSONArray("children").length());

        List<Object> same = new ArrayList<>();
        for (int i = 0; i < 4_990; i++) {
            same.add(i % 2 == 0 ? props("a", i, "b", "x") : props("b", "y", "a", i));
        }
        wire = Util.remapProperties(extractor.extractSchema(props("same", same), false));
        assertEquals(1, wire.getJSONObject(0).getJSONArray("children").length());
    }

    @Test
    public void jsonObjectInput() {
        JSONObject input = new JSONObject();
        input.put("list", new JSONArray().put(1).put(2));
        assertWire("[{propertyName:list,propertyType:'list(int)',children:[int]}]", input);
    }

    @Test
    public void jsonNullIsNull() {
        JSONObject input = new JSONObject();
        input.put("n", JSONObject.NULL);
        assertWire("[{propertyName:n,propertyType:'null'}]", input);
    }

    public static class Pojo {
        public String name = "n";
        public long count = 2;
        public double ratio = 0.0;
        public java.util.List<String> tags = Arrays.asList("a");
    }

    @Test
    public void pojoFieldsAreExtracted() {
        assertWire("[{propertyName:name,propertyType:string},{propertyName:count,propertyType:int},"
                        + "{propertyName:ratio,propertyType:float},"
                        + "{propertyName:tags,propertyType:'list(string)',children:[string]}]",
                new Pojo());
    }

    @Test
    public void listOfUnrecognisedValuesIsListOfObject() {
        // "list(unknown)" is not a wire type (SPEC.md §7.3.4); an unrecognised scalar stays "unknown".
        assertWire("[{propertyName:things,propertyType:'list(object)',children:[unknown]},"
                        + "{propertyName:thing,propertyType:unknown}]",
                props("things", Arrays.asList(new Object(), new Object()), "thing", new Object()));
    }

    @Test
    public void nullInputIsEmpty() {
        assertEquals(0, extractor.extractSchema(null, false).size());
    }

    @Test
    public void deepNestingIsTruncatedAtTenLevels() {
        // SPEC.md §9.3.2: 12 distinct nested maps, cut at 10 levels.
        Map<String, Object> deep = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("child", deep);
            deep = parent;
        }
        JSONArray wire = Util.remapProperties(extractor.extractSchema(deep, false));

        int depth = 0;
        JSONObject entry = wire.getJSONObject(0);
        while (entry.getJSONArray("children").length() > 0) {
            assertEquals("object", entry.getString("propertyType"));
            entry = entry.getJSONArray("children").getJSONObject(0);
            depth++;
        }
        assertEquals("object", entry.getString("propertyType"));
        assertEquals(10, depth);
    }

    @Test
    public void listElementAtTheDepthCapIsTheTypeObject() {
        // SPEC.md §9.3.2, as in Go and C#: a list leaf past the cap is "object", not [].
        List<Object> deep = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            List<Object> parent = new ArrayList<>();
            parent.add(deep);
            deep = parent;
        }
        JSONArray wire = Util.remapProperties(extractor.extractSchema(props("v", deep), false));

        JSONObject entry = wire.getJSONObject(0);
        assertEquals("list(object)", entry.getString("propertyType"));
        Object child = entry.getJSONArray("children");
        int depth = 0;
        while (child instanceof JSONArray) {
            JSONArray children = (JSONArray) child;
            assertEquals(1, children.length());
            child = children.get(0);
            depth++;
        }
        assertEquals("object", child);
        assertEquals(10, depth);
    }

    @Test(timeout = 5_000)
    public void aMapThatHoldsItselfIsCutAtTheCycle() {
        // Held under 10 keys, depth-capping alone would expand 10^10 nodes.
        Map<String, Object> cyclic = new LinkedHashMap<>();
        StringBuilder expected = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            cyclic.put("k" + i, cyclic);
            expected.append(i == 0 ? "" : ",").append("{propertyName:k").append(i).append(",propertyType:object,children:[]}");
        }
        assertWire(expected.append("]").toString(), cyclic);
    }

    @Test(timeout = 5_000)
    public void aListThatHoldsItselfIsCutAtTheCycle() {
        List<Object> cyclic = new ArrayList<>();
        Map<String, Object> parent = props("l", cyclic);
        cyclic.add(cyclic);
        cyclic.add(parent);
        cyclic.add(cyclic);
        // The list itself and its parent map are ancestors: each is the type string "object", deduplicated.
        assertWire("[{propertyName:l,propertyType:'list(object)',children:[object]}]", parent);
    }

    // Counts every pass over its elements (iterator() and toArray() both walk them).
    static final class CountingList<E> extends java.util.AbstractCollection<E> {
        final List<E> elements;
        int passes;

        CountingList(List<E> elements) {
            this.elements = elements;
        }

        @Override
        public java.util.Iterator<E> iterator() {
            passes++;
            return elements.iterator();
        }

        @Override
        public Object[] toArray() {
            passes++;
            return elements.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            passes++;
            return elements.toArray(a);
        }

        @Override
        public int size() {
            return elements.size();
        }
    }

    @Test
    public void eachListIsWalkedOnce() {
        CountingList<Object> inner = new CountingList<Object>(Arrays.<Object>asList(1, 2));
        CountingList<Object> outer = new CountingList<Object>(Arrays.<Object>asList(inner, "a"));
        assertWire("[{propertyName:v,propertyType:'list(object)',children:[[int],string]}]", props("v", outer));
        assertEquals(1, outer.passes);
        assertEquals(1, inner.passes);
    }

    // Measured in bytes allocated by this thread, not wall time: boxing and mapping a million
    // elements allocates tens of MB, while the primitive path allocates a few objects.
    @Test
    public void primitiveArraysAreNotBoxedElementByElement() {
        com.sun.management.ThreadMXBean threads = allocationCounter();
        long self = Thread.currentThread().getId();
        int[] large = new int[1_000_000];
        List<Integer> boxed = new ArrayList<>(large.length);
        for (int i = 0; i < large.length; i++) {
            large[i] = i;
            boxed.add(i);
        }

        long start = threads.getThreadAllocatedBytes(self);
        Map<String, AvoEventSchemaType> schema = extractor.extractSchema(props("v", large), false);
        long primitiveBytes = threads.getThreadAllocatedBytes(self) - start;
        assertEquals("list(int)", schema.get("v").getReportedName());
        assertTrue("int[] extraction allocated " + primitiveBytes + " bytes", primitiveBytes < 1_000_000);

        // Control: the same elements in a List are walked one by one, which the bound must catch.
        start = threads.getThreadAllocatedBytes(self);
        assertEquals("list(int)", extractor.extractSchema(props("v", boxed), false).get("v").getReportedName());
        long walkedBytes = threads.getThreadAllocatedBytes(self) - start;
        assertTrue("List<Integer> extraction allocated " + walkedBytes + " bytes", walkedBytes >= 1_000_000);
    }

    private static com.sun.management.ThreadMXBean allocationCounter() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        Assume.assumeTrue(threads.isThreadAllocatedMemorySupported());
        threads.setThreadAllocatedMemoryEnabled(true);
        return threads;
    }

    // Depth rule shared with Node and C#: top-level properties are at depth 0, every descent into a
    // property value or list element adds 1, and a complex value at depth 10 is cut.
    @Test
    public void nestedListsAreCutAtDepthTen() {
        Object value = Collections.singletonList(1);
        for (int i = 0; i < 10; i++) {
            value = Collections.singletonList(value);
        }
        // Verbatim cross-SDK reference output (Node, Go, C#).
        assertWire("[{\"propertyName\":\"a\",\"propertyType\":\"list(object)\",\"children\":[[[[[[[[[[\"object\"]]]]]]]]]]}]",
                props("a", value));
    }

    @Test
    public void alternatingListsAndObjectsAreCutAtDepthTen() {
        // {"a":[{"b":[{"c":[{"d":[{"e":[{"f":[{"g":1}]}]}]}]}]}]}
        Object fList = Collections.singletonList(props("g", 1));
        Object eList = Collections.singletonList(props("f", fList));
        Object dList = Collections.singletonList(props("e", eList));
        Object cList = Collections.singletonList(props("d", dList));
        Object bList = Collections.singletonList(props("c", cList));
        Object a = Collections.singletonList(props("b", bList));
        // Verbatim cross-SDK reference output (Node, Go, C#).
        assertWire("[{\"propertyName\":\"a\",\"propertyType\":\"list(object)\",\"children\":[[{\"propertyName\":\"b\",\"propertyType\":\"list(object)\",\"children\":[[{\"propertyName\":\"c\",\"propertyType\":\"list(object)\",\"children\":[[{\"propertyName\":\"d\",\"propertyType\":\"list(object)\",\"children\":[[{\"propertyName\":\"e\",\"propertyType\":\"list(object)\",\"children\":[[{\"propertyName\":\"f\",\"propertyType\":\"object\",\"children\":[]}]]}]]}]]}]]}]]}]",
                props("a", a));
    }

    @Test
    public void extractSchemaNeverThrows() {
        Map<String, Object> cyclicList = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        list.add(list);
        cyclicList.put("l", list);
        AvoInspector inspector = new AvoInspector("key", "1.0.0", "app", AvoInspectorEnv.Dev);
        try {
            inspector.extractSchema(cyclicList);
        } finally {
            inspector.destroy();
        }
    }
}
