package fixture;
public final class RenameFamily {
    public interface Reader { int a(int value); }
    public static class Base implements Reader { public int a(int value) { return value + 1; } }
    public static final class Child extends Base { @Override public int a(int value) { return value + 2; } }
    public static final class Peer implements Reader { public int a(int value) { return value + 3; } }
    public static final class Unrelated { public int a(int value) { return value + 4; } }
    public static int call(Reader r, int value) { return r.a(value); }
}
