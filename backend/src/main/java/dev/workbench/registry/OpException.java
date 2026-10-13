package dev.workbench.registry;

import java.util.LinkedHashMap;
import java.util.Map;

/** Error model per MCP_SPEC §3: code + human-readable message + machine detail. */
public class OpException extends Exception {

    public final String code;
    public final String detail;
    /** Extra machine fields the error carries (a not-found symbol's `candidates`, a facade
     *  `hint`) — merged into toMap() after code/message/detail. */
    public final Map<String, Object> extra = new LinkedHashMap<>();

    public OpException(String code, String message, String detail) {
        super(message);
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    public static OpException unsupported(String message, String detail) {
        return new OpException("E_UNSUPPORTED", message, detail);
    }

    public static OpException scope(String message, String detail) {
        return new OpException("E_SCOPE", message, detail);
    }

    public static OpException notFound(String message, String detail) {
        return new OpException("E_NOTFOUND", message, detail);
    }

    public static OpException io(String message, String detail) {
        return new OpException("E_IO", message, detail);
    }

    public static OpException busy(String message, String detail) {
        return new OpException("E_BUSY", message, detail);
    }

    public static OpException syntax(String message, String detail) {
        return new OpException("E_SYNTAX", message, detail);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", getMessage());
        m.put("detail", detail);
        m.putAll(extra);
        return m;
    }
}
