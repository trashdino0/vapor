import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads every class of a jar and touches its declared members — a cheap
 * structural smoke test for obfuscated output (broken references surface as
 * NoClassDefFoundError / VerifyError / ClassFormatError here).
 *
 * Usage: java -Xverify:all tools/LoadCheck.java <jar> [<jar> ...]
 */
public final class LoadCheck {

    private LoadCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java [-Xverify:all] LoadCheck.java <jar> [<jar> ...]");
            System.exit(2);
        }
        int failures = 0;
        int total = 0;
        for (String arg : args) {
            Path jar = Path.of(arg);
            List<String> names = new ArrayList<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                        names.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    }
                }
            }
            total += names.size();
            try (URLClassLoader loader = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()}, LoadCheck.class.getClassLoader())) {
                for (String name : names) {
                    try {
                        Class<?> type = Class.forName(name, false, loader);
                        type.getDeclaredMethods(); // force resolution of member signatures
                        type.getDeclaredFields();
                    } catch (Throwable t) {
                        failures++;
                        System.out.println("FAIL " + jar.getFileName() + " :: " + name + " : " + t);
                    }
                }
            }
            System.out.println("loaded " + names.size() + " classes from " + jar.getFileName());
        }
        System.out.println("total=" + total + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
