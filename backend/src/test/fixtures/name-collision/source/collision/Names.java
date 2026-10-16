package collision;

/** Authored collision fixture: the blocking method is on another override family member. */
public class Names {
    public static class Base { public int a(int value) { return value + 1; } }
    public static class Child extends Base {
        @Override public int a(int value) { return value + 2; }
        public int occupied(int value) { return value - 1; }
    }
    public static class Peer extends Base { @Override public int a(int value) { return value + 3; } }
}
