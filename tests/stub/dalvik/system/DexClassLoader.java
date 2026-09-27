package dalvik.system;
/** Desktop stub so the Android-only payload resolver compiles under a JVM. */
public class DexClassLoader extends ClassLoader {
    public DexClassLoader(String dexPath, String optimizedDirectory,
                          String librarySearchPath, ClassLoader parent) {
        super(parent);
    }
}
