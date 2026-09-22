package org.jobrunr.dashboard.mcp;

/**
 * Replaces the value of every job parameter in a dashboard API response by {@code "<redacted>"}, so job parameter values never
 * leave the JVM while the parameter types stay visible. Works on the JSON text itself and is independent of the configured JsonMapper.
 */
class JobParameterRedactor {

    static final String REDACTED = "\"<redacted>\"";

    private enum Scope {OTHER, JOB_PARAMETERS, JOB_PARAMETER}

    private final String json;
    private final StringBuilder result;
    private int pos;

    private JobParameterRedactor(String json) {
        this.json = json;
        this.result = new StringBuilder(json.length());
    }

    /**
     * Returns the redacted json, the json itself if it holds no job parameters or null if it could not be parsed.
     */
    static String redact(String json) {
        if (!json.contains("\"jobParameters\"")) return json;
        try {
            JobParameterRedactor redactor = new JobParameterRedactor(json);
            redactor.copyValue(Scope.OTHER);
            redactor.copyWhitespace();
            return redactor.pos == json.length() ? redactor.result.toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void copyValue(Scope scope) {
        copyWhitespace();
        char c = json.charAt(pos);
        if (c == '{') {
            copyObject(scope);
        } else if (c == '[') {
            copyArray(scope);
        } else if (c == '"') {
            result.append(readString());
        } else {
            copyLiteral();
        }
    }

    private void copyObject(Scope scope) {
        expect('{');
        copyWhitespace();
        if (json.charAt(pos) == '}') {
            expect('}');
            return;
        }
        do {
            copyWhitespace();
            String key = readString();
            result.append(key);
            copyWhitespace();
            expect(':');
            if (scope == Scope.JOB_PARAMETER && "\"object\"".equals(key)) {
                skipValue();
                result.append(REDACTED);
            } else {
                copyValue("\"jobParameters\"".equals(key) ? Scope.JOB_PARAMETERS : Scope.OTHER);
            }
            copyWhitespace();
        } while (copyIfPresent(','));
        expect('}');
    }

    private void copyArray(Scope scope) {
        expect('[');
        copyWhitespace();
        if (json.charAt(pos) == ']') {
            expect(']');
            return;
        }
        do {
            copyValue(scope == Scope.JOB_PARAMETERS ? Scope.JOB_PARAMETER : Scope.OTHER);
            copyWhitespace();
        } while (copyIfPresent(','));
        expect(']');
    }

    private void skipValue() {
        int length = result.length();
        copyValue(Scope.OTHER);
        result.setLength(length);
    }

    private String readString() {
        int start = pos;
        if (json.charAt(pos) != '"') throw new IllegalStateException("Expected a string at " + pos);
        pos++;
        while (json.charAt(pos) != '"') {
            pos += json.charAt(pos) == '\\' ? 2 : 1;
        }
        pos++;
        return json.substring(start, pos);
    }

    private void copyLiteral() {
        int start = pos;
        while (pos < json.length() && ",}] \t\r\n".indexOf(json.charAt(pos)) < 0) {
            pos++;
        }
        if (start == pos) throw new IllegalStateException("Expected a value at " + pos);
        result.append(json, start, pos);
    }

    private void copyWhitespace() {
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
            result.append(json.charAt(pos++));
        }
    }

    private boolean copyIfPresent(char c) {
        if (json.charAt(pos) != c) return false;
        expect(c);
        return true;
    }

    private void expect(char c) {
        if (json.charAt(pos) != c) throw new IllegalStateException("Expected '" + c + "' at " + pos);
        result.append(c);
        pos++;
    }
}
