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
// expand exponentially. The expected digests are Node's (dist/AvoSchemaParser.js) output for the
// same inputs, reduced with the same canonical form.
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

    @Test(timeout = 10_000)
    public void mapFanOutFourIsCutByTheBudgetLikeNode() throws Exception {
        assertMatchesNode(mapsDag(4, 12), 590002, "3c1ab356addf62e89ce1618b32dfdae9303eda1fe2276e14ab3ca785d563e0a5",
                40000, 30001, 0);
    }

    @Test(timeout = 10_000)
    public void listFanOutSixIsCutByTheBudgetLikeNode() throws Exception {
        assertMatchesNode(listDag(6, 12), 178576, "3dc637ef3e554a0e1aecec9dcec0933e51c94b66f6064e7be996f1ee8c1a5670",
                8570, 7140, 3);
    }
}
