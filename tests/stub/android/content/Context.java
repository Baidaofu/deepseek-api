package android.content;
public class Context {
    public android.content.Context getApplicationContext() { return this; }
    public java.io.File getCodeCacheDir() { return new java.io.File("."); }
    // ---- test hooks ----
    public static final java.util.Map<String,java.util.Map<String,Object>> PREFS =
        new java.util.HashMap<String,java.util.Map<String,Object>>();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        java.util.Map<String,Object> store = PREFS.get(name);
        if (store == null) { store = new java.util.HashMap<String,Object>(); PREFS.put(name, store); }
        return new SharedPreferences(store);
    }
}
