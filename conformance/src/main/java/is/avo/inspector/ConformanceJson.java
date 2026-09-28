package is.avo.inspector;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal JSON reader for the conformance harness. Unlike org.json it keeps object key order
 * (fixtures assert property order) and keeps integer literals integral and decimal literals
 * floating point (SPEC.md §9.3.1.1). Objects become LinkedHashMap, arrays ArrayList, integers
 * Long (BigInteger when larger), other numbers Double, and JSON null becomes Java null.
 */
final class ConformanceJson {

    private final String text;
    private int pos;

    private ConformanceJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        ConformanceJson parser = new ConformanceJson(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    private Object readValue() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected object key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return result;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return result;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return result.toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in string");
            }
            if (c != '\\') {
                result.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"': result.append('"'); break;
                case '\\': result.append('\\'); break;
                case '/': result.append('/'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length() || !HEX4.matcher(text.substring(pos, pos + 4)).matches()) {
                        throw error("bad unicode escape");
                    }
                    result.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw error("bad escape '\\" + escape + "'");
            }
        }
    }

    private static final Pattern HEX4 = Pattern.compile("[0-9a-fA-F]{4}");
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private Object readNumber() {
        int start = pos;
        boolean integral = true;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '.' || c == 'e' || c == 'E') {
                integral = false;
            } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                break;
            }
            pos++;
        }
        String literal = text.substring(start, pos);
        // Parsers below accept more than JSON does (e.g. "01", "1.", "+1").
        if (!NUMBER.matcher(literal).matches()) {
            throw error("bad number '" + literal + "'");
        }
        try {
            if (integral) {
                BigInteger value = new BigInteger(literal);
                return value.bitLength() < 64 ? (Object) value.longValue() : value;
            }
            return Double.parseDouble(literal);
        } catch (NumberFormatException e) {
            throw error("bad number '" + literal + "'");
        }
    }

    private void expectWord(String word) {
        if (!text.startsWith(word, pos)) {
            throw error("expected '" + word + "'");
        }
        pos += word.length();
    }

    private void expect(char expected) {
        if (next() != expected) {
            throw error("expected '" + expected + "'");
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
    }
}
