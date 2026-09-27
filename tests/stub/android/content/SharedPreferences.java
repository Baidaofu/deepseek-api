package android.content;
public class SharedPreferences {
    public static class Editor {
        private final java.util.Map<String,Object> store;
        private final java.util.Map<String,Object> pending = new java.util.HashMap<String,Object>();
        Editor(java.util.Map<String,Object> store) { this.store = store; }
        public Editor putString(String k, String v) { pending.put(k, v); return this; }
        public Editor putInt(String k, int v) { pending.put(k, v); return this; }
        public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
        public void apply() { store.putAll(pending); }
        public boolean commit() { store.putAll(pending); return true; }
    }
    private final java.util.Map<String,Object> store;
    SharedPreferences(java.util.Map<String,Object> store) { this.store = store; }
    public String getString(String k, String def) {
        Object v = store.get(k); return v instanceof String ? (String) v : def;
    }
    public int getInt(String k, int def) { Object v = store.get(k); return v instanceof Integer ? (Integer) v : def; }
    public boolean getBoolean(String k, boolean def) { Object v = store.get(k); return v instanceof Boolean ? (Boolean) v : def; }
    public Editor edit() { return new Editor(store); }
}
