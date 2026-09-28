package is.avo.inspector;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

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
    public void nestedListsOfPrimitivesAreNotMerged() {
        // SPEC.md §9.3.4: [[1], [2]] -> list(object) with one child per element.
        assertWire("[{propertyName:v,propertyType:'list(object)',children:[[int],[int]]}]",
                props("v", Arrays.asList(Collections.singletonList(1), Collections.singletonList(2))));
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
        // SPEC.md §9.3.2: a self-referencing map must not overflow the stack.
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        JSONArray wire = Util.remapProperties(extractor.extractSchema(cyclic, false));

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
