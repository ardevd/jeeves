package dev.workbench.engine;

/**
 * Names derived from Dalvik type descriptors. Shared by the engine adapters and the Workspace's
 * rename follow-ups (a renamed class renames the locals typed with it), and kept outside the JEB
 * adapter package so a build without JEB does not depend on it.
 */
public final class TypeNames {

    private TypeNames() {}

    /** The reserved words a generated name must not be (the Engine's list, shared with name validation). */
    public static final java.util.Set<String> JAVA_KEYWORDS = Engine.JAVA_KEYWORDS;

    /** The local name a declared type suggests: Lm76/DownloadHelper; → downloadHelper, URL → url,
     *  HTTPClient → httpClient, [B → bytes, [Lm76/X; → xs; a primitive → null (not named after its type). */
    public static String nameForType(String sig) {
        if (sig == null || sig.isEmpty()) return null;
        if (sig.startsWith("[")) {
            String elem = sig.substring(1);
            String prim = switch (elem) {
                case "B" -> "bytes"; case "I" -> "ints"; case "J" -> "longs"; case "Z" -> "booleans"; case "C" -> "chars";
                case "S" -> "shorts"; case "F" -> "floats"; case "D" -> "doubles"; default -> null;
            };
            if (prim != null) return prim;
            String inner = nameForType(elem);
            return inner == null ? null : inner.endsWith("s") ? inner + "Array" : inner + "s";
        }
        if (!sig.startsWith("L") || !sig.endsWith(";")) return null;      // a primitive
        String simple = sig.substring(1, sig.length() - 1);
        int cut = Math.max(simple.lastIndexOf('/'), simple.lastIndexOf('$'));
        if (cut >= 0) simple = simple.substring(cut + 1);
        if (simple.isEmpty()) return null;
        String name = lowerCamel(simple);
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) name = "_" + name;   // an obfuscated `0dP`
        if (JAVA_KEYWORDS.contains(name)) name = name + "Var";
        return name;
    }

    static String lowerCamel(String s) {
        if (s.length() > 1 && s.equals(s.toUpperCase()) && s.matches("[A-Z0-9_]+")) return s.toLowerCase();
        int i = 0;
        while (i < s.length() && Character.isUpperCase(s.charAt(i))) i++;
        if (i == 0) return s;
        if (i == 1 || i == s.length()) return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        return s.substring(0, i - 1).toLowerCase() + s.substring(i - 1);   // HTTPClient → httpClient
    }
}
