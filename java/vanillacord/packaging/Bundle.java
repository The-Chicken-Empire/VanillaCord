package vanillacord.packaging;

import bridge.asm.HierarchyScanner;
import org.objectweb.asm.ClassReader;
import vanillacord.Patcher;
import vanillacord.data.Digest;
import vanillacord.data.HierarchyVisitor;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Bundle extends Package {
    private final HashSet<String> unique = new HashSet<>();
    private final String format;
    private String[] server;
    private File file;

    public Bundle(String format) {
        this.format = format;
    }

    List<String[]> load(ClassLoader bundle, String name, Consumer<ClassReader> action) throws IOException {
        InputStream stream = bundle.getResourceAsStream("META-INF/" + name + ".list");
        if (stream == null) {
            throw new FileNotFoundException(name + ".list");
        }
        BufferedReader list = new BufferedReader(new InputStreamReader(stream, UTF_8));
        List<String[]> files = new ArrayList<>();
        for (String file; (file = list.readLine()) != null;) {
            String[] data = file.split("\t", 4);
            if (data.length != 3) throw new IllegalStateException("Unknown " + name + ".list format: " + file);
            files.add(data);

            if ((stream = bundle.getResourceAsStream("META-INF/" + name + '/' + (file = data[2]))) == null) {
                throw new FileNotFoundException(name + '/' + file);
            }
            System.out.print("Loading ");
            System.out.println(file);
            try (ZipInputStream zis = new ZipInputStream(stream)) {
                for (ZipEntry entry; (entry = zis.getNextEntry()) != null;) {
                    if (entry.getName().endsWith(".class") && unique.add(entry.getName())) {
                        action.accept(new ClassReader(zis));
                    }
                }
            }
        }

        return files;
    }

    @Override
    public ZipInputStream read(File file) throws Throwable {
        URLClassLoader bundle = new URLClassLoader(new URL[]{ (this.file = file).toURI().toURL() }, null);
        load(bundle, "libraries", reader -> reader.accept(new HierarchyScanner(types), ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG));
        List<String[]> versions = load(bundle, "versions", reader -> reader.accept(new HierarchyVisitor(this), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG));
        if (versions.size() != 1) throw new IllegalStateException("More or less than one version was distributed");
        return new ZipInputStream(bundle.getResourceAsStream("META-INF/versions/" + (server = versions.get(0))[2]));
    }

    @Override
    public ZipOutputStream write(File file) throws Throwable {
        if (server == null) throw new IllegalStateException("Called to write() before read()");
        ZipInputStream zis = new ZipInputStream(new FileInputStream(this.file));
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file, false));
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        HashSet<String> unique = new HashSet<>();

        for (ZipEntry entry; (entry = zis.getNextEntry()) != null;) {
            if (unique.add(entry.getName()) && patch(zis, zos, entry.getName())) {
                return new ZipOutputStream(new OutputStream() {

                    @Override
                    public void close() throws IOException {
                        Bundle.this.close(zos, sha256.digest());
                        for (ZipEntry entry; (entry = zis.getNextEntry()) != null;) {
                            if (unique.add(entry.getName())) patch(zis, zos, entry.getName());
                        }
                        zis.close();
                        zos.close();
                    }

                    @Override
                    public void write(int b) throws IOException {
                        zos.write(b);
                        sha256.update((byte) b);
                    }

                    @Override
                    public void write(byte[] b) throws IOException {
                        zos.write(b);
                        sha256.update(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        zos.write(b, off, len);
                        sha256.update(b, off, len);
                    }
                });
            }
        }
        throw new IllegalStateException("Server jarfile disappeared");
    }

    boolean patch(ZipInputStream zis, ZipOutputStream zos, String entry) throws IOException {
        if (entry.equals("META-INF/MANIFEST.MF")) {
            zos.putNextEntry(new ZipEntry(entry));
            Patcher.manifest(zis, zos);
        } else if (entry.equals("META-INF/versions/" + server[2])) {
            zos.putNextEntry(new ZipEntry(entry));
            return true;
        } else if (!entry.equals("META-INF/versions.list") && (!entry.startsWith("META-INF/") || !entry.endsWith(".SF"))) {
            zos.putNextEntry(new ZipEntry(entry));
            Patcher.copy(zis, zos);
        }
        return false;
    }

    void close(ZipOutputStream zos, byte[] sha256) throws IOException {
        server[0] = Digest.toHex(sha256);
        zos.putNextEntry(new ZipEntry("META-INF/versions.list"));
        zos.write(String.join("\t", server).getBytes(UTF_8));
    }
}
