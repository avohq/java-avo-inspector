package is.avo.inspector;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// At most 10,000 property entries per extraction call, counted at every depth in iteration order
// (the cross-SDK extraction bound); entries past it are omitted.
public class PropertyBudgetTests {

    private static Map<String, AvoEventSchemaType> extract(Object properties) {
        return new AvoSchemaExtractor().extractSchema(properties, false);
    }

    // Property entries at every depth, including those of objects inside lists.
    static int entries(Map<String, AvoEventSchemaType> schema) {
        int n = 0;
        for (AvoEventSchemaType type : schema.values()) {
            n += 1 + nested(type);
        }
        return n;
    }

    private static int nested(AvoEventSchemaType type) {
        if (type instanceof AvoEventSchemaType.AvoObject) {
            return entries(((AvoEventSchemaType.AvoObject) type).children);
        }
        int n = 0;
        if (type instanceof AvoEventSchemaType.AvoList) {
            for (AvoEventSchemaType child : ((AvoEventSchemaType.AvoList) type).children) {
                n += nested(child);
            }
        }
        return n;
    }

    private static Map<String, Object> flat(int keys) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keys; i++) {
            map.put("key" + i, i);
        }
        return map;
    }

    @Test(timeout = 10_000)
    public void aMapWithAMillionKeysKeepsTheFirstTenThousand() {
        Map<String, Object> input = flat(1_000_000);
        long start = System.nanoTime();
        Map<String, AvoEventSchemaType> schema = extract(input);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals(10_000, schema.size());
        assertEquals(new ArrayList<>(input.keySet()).subList(0, 10_000), new ArrayList<>(schema.keySet()));
        int body = Util.remapProperties(schema).toString().length();
        assertTrue("body " + body, body < 600_000);
        assertTrue("took " + ms + " ms", ms < 2_000);
    }

    @Test(timeout = 10_000)
    public void aJsonObjectWithAMillionKeysKeepsTenThousandInItsIterationOrder() {
        JSONObject input = new JSONObject();
        for (int i = 0; i < 1_000_000; i++) {
            input.put("key" + i, i);
        }
        List<String> order = new ArrayList<>();
        for (java.util.Iterator<String> it = input.keys(); it.hasNext() && order.size() < 10_000; ) {
            order.add(it.next());
        }
        Map<String, AvoEventSchemaType> schema = extract(input);

        assertEquals(order, new ArrayList<>(schema.keySet()));
        assertTrue(Util.remapProperties(schema).toString().length() < 600_000);
    }

    @Test
    public void nestedPropertiesShareTheBudget() {
        // "a" (1) + its 5,000 children, then "b" (1) + 4,998 of its children: 10,000.
        Map<String, Object> input = new LinkedHashMap<>();
        for (String key : Arrays.asList("a", "b", "c", "d", "e")) {
            input.put(key, flat(5_000));
        }
        Map<String, AvoEventSchemaType> schema = extract(input);

        assertEquals(Arrays.asList("a", "b"), new ArrayList<>(schema.keySet()));
        assertEquals(5_000, ((AvoEventSchemaType.AvoObject) schema.get("a")).children.size());
        Map<String, AvoEventSchemaType> b = ((AvoEventSchemaType.AvoObject) schema.get("b")).children;
        assertEquals(4_998, b.size());
        assertEquals("key4997", new ArrayList<>(b.keySet()).get(4_997));
        assertEquals(10_000, entries(schema));
    }

    @Test
    public void propertiesOfObjectsInsideListsCount() {
        // "items" (1) + 4,000 + 4,000 + 1,999 properties of the three maps in the list.
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            items.add(flat(4_000));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", items);
        input.put("after", 1);
        Map<String, AvoEventSchemaType> schema = extract(input);

        assertEquals(Arrays.asList("items"), new ArrayList<>(schema.keySet()));
        List<AvoEventSchemaType> children = ((AvoEventSchemaType.AvoList) schema.get("items")).children;
        assertEquals(3, children.size());
        assertEquals(4_000, ((AvoEventSchemaType.AvoObject) children.get(0)).children.size());
        assertEquals(4_000, ((AvoEventSchemaType.AvoObject) children.get(1)).children.size());
        assertEquals(1_999, ((AvoEventSchemaType.AvoObject) children.get(2)).children.size());
        assertEquals(10_000, entries(schema));
    }

    @Test
    public void exactlyTenThousandPropertiesAreAllKept() {
        assertEquals(10_000, extract(flat(10_000)).size());
        Map<String, AvoEventSchemaType> over = extract(flat(10_001));
        assertEquals(10_000, over.size());
        assertTrue(!over.containsKey("key10000"));
    }

    @Test
    public void aSchemaUnderTheBudgetIsUnchanged() {
        // 100 maps of 98 properties: 9,900 entries, all kept.
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            input.put("m" + i, flat(98));
        }
        Map<String, AvoEventSchemaType> schema = extract(input);
        assertEquals(100, schema.size());
        assertEquals(9_900, entries(schema));
        for (AvoEventSchemaType type : schema.values()) {
            assertEquals(98, ((AvoEventSchemaType.AvoObject) type).children.size());
        }
    }

    // A plain object's fields are properties too.
    static final class Wide {
        final int a = 1;
        final Map<String, Object> wide = flat(20_000);
        final int z = 2;
    }

    @Test
    public void aPlainObjectsFieldsCount() {
        Map<String, AvoEventSchemaType> schema = extract(new Wide());
        assertEquals(10_000, entries(schema));
        assertEquals(Arrays.asList("a", "wide"), new ArrayList<>(schema.keySet()));
    }
}
