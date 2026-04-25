package com.transcription.core;

/**
 * Tiny zero-dependency JSON helper. Only does what this app needs:
 * escaping and extracting the value of a single named string field.
 *
 * <p>For anything more, swap in a real parser. We use this on the hot path so
 * that the {@code :core} library has no transitive dependencies and ships as
 * a tiny jar.
 */
final class Json {

    private Json() {}

    /**
     * Escapes the given raw string so it can be embedded as a JSON string
     * literal (i.e. the characters between the surrounding quotes).
     */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b");  break;
                case '\f': out.append("\\f");  break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /**
     * Returns the value of the first {@code "field":"..."} pair whose key
     * exactly matches {@code field}, or {@code null} if no such pair is found.
     * Handles the standard JSON string escapes (backslash, quote, b, f, n,
     * r, t, slash, and 4-digit hex unicode escapes). Whitespace between the
     * colon and the opening quote is tolerated.
     *
     * <p>This is deliberately *not* a full JSON parser — it's enough for the
     * shapes Ollama returns: {@code {"response":"...", "done":true}} and
     * {@code {"error":"..."}}.
     */
    static String extractString(String json, String field) {
        String needle = "\"" + field + "\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(needle, from);
            if (k < 0) return null;
            int i = k + needle.length();
            // skip whitespace
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != ':') {
                from = k + 1;
                continue;
            }
            i++; // past ':'
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != '"') {
                // Field exists but value isn't a string. For our purposes that
                // means "no string value" — bail.
                return null;
            }
            i++; // past opening quote
            StringBuilder out = new StringBuilder();
            while (i < json.length()) {
                char c = json.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= json.length()) {
                    throw new IllegalArgumentException("Truncated escape at end of JSON");
                }
                char esc = json.charAt(i++);
                switch (esc) {
                    case '"':  out.append('"');  break;
                    case '\\': out.append('\\'); break;
                    case '/':  out.append('/');  break;
                    case 'b':  out.append('\b'); break;
                    case 'f':  out.append('\f'); break;
                    case 'n':  out.append('\n'); break;
                    case 'r':  out.append('\r'); break;
                    case 't':  out.append('\t'); break;
                    case 'u':
                        if (i + 4 > json.length()) {
                            throw new IllegalArgumentException("Truncated \\u escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                        } catch (NumberFormatException nfe) {
                            throw new IllegalArgumentException("Truncated \\u escape", nfe);
                        }
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown escape \\" + esc);
                }
            }
            throw new IllegalArgumentException("Unterminated string for field \"" + field + "\"");
        }
    }
}
