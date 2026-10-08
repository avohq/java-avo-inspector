package is.avo.inspector;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

// A per-call expansion budget, as in Node: shared references that are not cycles would otherwise
// expand exponentially; and a per-call budget of 10,000 property entries (MAX_PROPERTIES). The
// digests are of the canonical form below, compared across the SDKs for the same inputs.
public class ExpansionBudgetTests {

    private static Map<String, Object> mapsDag(int fan, int depth) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("v", 1);
        for (int d = 0; d < depth; d++) {
            Map<String, Object> level = new LinkedHashMap<>();
            for (int k = 0; k < fan; k++) {
                level.put("k" + k, child);
            }
            child = level;
        }
        return child;
    }

    private static Map<String, Object> listDag(int fan, int depth) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("v", 1.5);
        for (int d = 0; d < depth; d++) {
            List<Object> items = new ArrayList<>();
            for (int k = 0; k < fan; k++) {
                items.add(child);
            }
            Map<String, Object> level = new LinkedHashMap<>();
            level.put("items", items);
            child = level;
        }
        return child;
    }

    // {name|type[|children]} for entries, [a,b] for arrays, JSON strings for type strings.
    private static void canon(Object value, StringBuilder out) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            out.append('[');
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                canon(array.get(i), out);
            }
            out.append(']');
        } else if (value instanceof JSONObject) {
            JSONObject entry = (JSONObject) value;
            out.append('{').append(entry.getString("propertyName")).append('|').append(entry.getString("propertyType"));
            if (entry.has("children")) {
                out.append('|');
                canon(entry.get("children"), out);
            }
            out.append('}');
        } else {
            out.append(JSONObject.quote((String) value));
        }
    }

    private static int occurrences(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private static void assertMatchesNode(Map<String, Object> input, int length, String sha256, int entries,
                                          int leafObjects, int objectStrings) throws Exception {
        StringBuilder out = new StringBuilder();
        canon(Util.remapProperties(new AvoSchemaExtractor().extractSchema(input, false)), out);
        String canonical = out.toString();

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        assertEquals(length, canonical.length());
        assertEquals(entries, occurrences(canonical, "{"));
        assertEquals(leafObjects, occurrences(canonical, "|object|[]}"));
        assertEquals(objectStrings, occurrences(canonical, "\"object\""));
        assertEquals(sha256, hex.toString());
    }

    // 40,000 entries under the expansion budget alone; the property budget keeps the first 10,000.
    @Test(timeout = 10_000)
    public void mapFanOutFourIsCutByBothBudgets() throws Exception {
        assertMatchesNode(mapsDag(4, 12), 147496, "5012bc5b9543195e969f2b10956402ef0306e92a009f409194dffd8bd46083ed",
                10000, 7495, 0);
    }

    private static Map<String, Object> flat(int keys) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keys; i++) {
            map.put("key" + i, i);
        }
        return map;
    }

    @Test(timeout = 10_000)
    public void aMillionKeyMapKeepsItsFirstTenThousandProperties() throws Exception {
        assertMatchesNode(flat(1_000_000), 138891, "9652d270cd6568d00b73032b56b73782771f634703c89f7fa81df852ae50c835",
                10000, 0, 0);
    }

    @Test(timeout = 10_000)
    public void nestedWideMapsShareThePropertyBudget() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        for (String key : new String[]{"a", "b", "c", "d", "e"}) {
            nested.put(key, flat(5_000));
        }
        assertMatchesNode(nested, 137779, "73549e68066367f3e976a97b299df3bb972e80fe65b487f89b7d7f251397984d",
                10000, 0, 0);
    }

    @Test(timeout = 10_000)
    public void listFanOutSixIsCutByTheBudgetLikeNode() throws Exception {
        // Equal list children are kept once, so only the subtrees the budget cut differ.
        assertMatchesNode(listDag(6, 12), 386, "fa36cee2d9450d07e0b374330e796ce55b03dc8e37b44af34c902d60b0c787e5",
                15, 3, 3);
    }
}
