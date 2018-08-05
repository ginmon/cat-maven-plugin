package io.solit.plugin.maven.cat;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author yaga
 * @since 03.08.18
 */
public class ConcatenationMojoTest {

    private static ConcatenatedFile concatenatedFile(String destination, boolean skipExisting, boolean append, String... parts) {
        ConcatenatedFile file = new ConcatenatedFile();
        file.file = destination;
        file.skipExisting = skipExisting;
        file.append = append;
        file.parts = Stream.of(parts).map(str -> {
            try {
                return new URI(str);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }).collect(Collectors.toList());
        return file;
    }

    private static ConcatenatedFile concatenatedFile(String destination, String... parts) {
        return concatenatedFile(destination, false, false, parts);
    }

    private static byte[] createEmptyZip() {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            new ZipOutputStream(os).close();
            return os.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] appendZip(byte[] zip, String fileName, byte[] fileContent) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try(
                ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip));
                ZipOutputStream zos = new ZipOutputStream(bos)
        ) {
            for(ZipEntry ze = zis.getNextEntry(); ze != null; ze = zis.getNextEntry()) {
                zos.putNextEntry(ze);
                org.apache.commons.io.IOUtils.copy(zis, zos);
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry(fileName));
            zos.write(fileContent);
            zos.closeEntry();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return bos.toByteArray();
    }

    @Test
    public void testEmptyMojo() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo();
        mojo.execute();
        mojo.assertNoFiles();
    }

    @Test
    public void testEmptyFile() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo(concatenatedFile("foo"));
        mojo.execute();
        assertEquals("", new String(mojo.fetchDestFile("foo"), StandardCharsets.UTF_8));
        mojo.assertNoFiles();
    }

    @Test
    public void testMojoWithDataURI() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo(
                concatenatedFile("foo", "data:,plain%20text"),
                concatenatedFile("bar", "data:;base64,YmFzZTY0IHRleHQ="),
                concatenatedFile("qux", "data:text/plain;charset=UTF-16;base64,bm90IGEgdXRmLTE2")
        );
        mojo.execute();
        assertEquals("plain text", new String(mojo.fetchDestFile("foo"), StandardCharsets.UTF_8));
        assertEquals("base64 text", new String(mojo.fetchDestFile("bar"), StandardCharsets.UTF_8));
        assertEquals("not a utf-16", new String(mojo.fetchDestFile("qux"), StandardCharsets.UTF_8));
        mojo.assertNoFiles();
    }

    @Test
    public void testMojoWithFileURI() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo(
                concatenatedFile("foo", "file:relative/file"),
                concatenatedFile("bar", "file:/absolute/file"),
                concatenatedFile("baz", "no/prefix"),
                concatenatedFile("qux", "/absolutely/no/prefix")
        );
        mojo.addSourceFile("relative/file", "prefixed relative".getBytes(StandardCharsets.UTF_8));
        mojo.addSourceFile("/absolute/file", "prefixed absolute".getBytes(StandardCharsets.UTF_8));
        mojo.addSourceFile("no/prefix", "relative".getBytes(StandardCharsets.UTF_8));
        mojo.addSourceFile("/absolutely/no/prefix", "absolute".getBytes(StandardCharsets.UTF_8));
        mojo.execute();
        assertEquals("prefixed relative", new String(mojo.fetchDestFile("foo"), StandardCharsets.UTF_8));
        assertEquals("prefixed absolute", new String(mojo.fetchDestFile("bar"), StandardCharsets.UTF_8));
        assertEquals("relative", new String(mojo.fetchDestFile("baz"), StandardCharsets.UTF_8));
        assertEquals("absolute", new String(mojo.fetchDestFile("qux"), StandardCharsets.UTF_8));
        mojo.assertNoFiles();
    }

    @Test
    public void testMojoWithArtifactURI() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo(
                concatenatedFile("foo", "maven:g:a:v"),
                concatenatedFile("bar", "maven:gr:ar:ve!/some/file")
        );
        mojo.addArtifact("g:a:jar:v", "plain content".getBytes(StandardCharsets.UTF_8));
        byte[] zip = createEmptyZip();
        zip = appendZip(zip, "some/wrong/file", "Wrong!".getBytes(StandardCharsets.UTF_8));
        zip = appendZip(zip, "some/file", "zipped content".getBytes(StandardCharsets.UTF_8));
        zip = appendZip(zip, "another/file", "Not right!".getBytes(StandardCharsets.UTF_8));
        mojo.addArtifact("gr:ar:jar:ve", zip);
        mojo.execute();
        assertEquals("plain content", new String(mojo.fetchDestFile("foo"), StandardCharsets.UTF_8));
        assertEquals("zipped content", new String(mojo.fetchDestFile("bar"), StandardCharsets.UTF_8));
        mojo.assertNoFiles();
    }

    @Test
    public void testConcatenation() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo(
                concatenatedFile("foo",
                        "data:,Hello",
                        "source/file",
                        "maven:g:a:v"
                )
        );
        mojo.addSourceFile("source/file", ", ".getBytes(StandardCharsets.UTF_8));
        mojo.addArtifact("g:a:jar:v", "world!".getBytes(StandardCharsets.UTF_8));
        mojo.execute();
        assertEquals("Hello, world!", new String(mojo.fetchDestFile("foo"), StandardCharsets.UTF_8));
        mojo.assertNoFiles();
    }

    @Test
    public void testOverride() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo(
                concatenatedFile("some/foo", "data:,bar"),
                concatenatedFile("some/qux", true, false, "data:,baz"),
                concatenatedFile("zyzyx", true, true, "data:,xyzzy")
        );
        mojo.addDestFile("some/foo", "Replaced".getBytes(StandardCharsets.UTF_8));
        mojo.addDestFile("some/qux", "Obsolete".getBytes(StandardCharsets.UTF_8));
        mojo.addDestFile("zyzyx", "Old".getBytes(StandardCharsets.UTF_8));
        mojo.execute();
        assertEquals("bar", new String(mojo.fetchDestFile("some/foo"), StandardCharsets.UTF_8));
        assertEquals("Obsolete", new String(mojo.fetchDestFile("some/qux"), StandardCharsets.UTF_8));
        assertEquals("Old", new String(mojo.fetchDestFile("zyzyx"), StandardCharsets.UTF_8));
        mojo.assertNoFiles();
    }

    @Test
    public void testAppends() throws MojoFailureException, MojoExecutionException {
        MockConcatenateMojo mojo = new MockConcatenateMojo(
                concatenatedFile("some/foo", "data:,bar"),
                concatenatedFile("some/qux", false, false, "data:,baz"),
                concatenatedFile("zyzyx", false, true, "data:,and%20new")
        );
        mojo.addDestFile("some/foo", "Replaced".getBytes(StandardCharsets.UTF_8));
        mojo.addDestFile("some/qux", "Obsolete".getBytes(StandardCharsets.UTF_8));
        mojo.addDestFile("zyzyx", "Old ".getBytes(StandardCharsets.UTF_8));
        mojo.execute();
        assertEquals("bar", new String(mojo.fetchDestFile("some/foo"), StandardCharsets.UTF_8));
        assertEquals("baz", new String(mojo.fetchDestFile("some/qux"), StandardCharsets.UTF_8));
        assertEquals("Old and new", new String(mojo.fetchDestFile("zyzyx"), StandardCharsets.UTF_8));
        mojo.assertNoFiles();
    }

    @Test
    public void testMissingResource() {
        assertThrows(MojoFailureException.class, () -> {
                    MockConcatenateMojo mojo = new MockConcatenateMojo(
                            concatenatedFile("some/foo", "some/file")
                    );
                    mojo.execute();
        });
        assertThrows(MojoFailureException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some/foo", "maven:g:a:v")
            );
            mojo.execute();
        });
        assertThrows(MojoFailureException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some/foo", "maven:g:a:v!/file")
            );
            mojo.addArtifact("g:a:jar:v", appendZip(createEmptyZip(), "other/file", new byte[0]));
            mojo.execute();
        });
    }

    @Test
    public void testMissingDestination() {
        assertThrows(
                MojoFailureException.class,
                () -> new MockConcatenateMojo(concatenatedFile(null)).execute()
        );
        assertThrows(
                MojoFailureException.class,
                () -> new MockConcatenateMojo(concatenatedFile("")).execute()
        );
    }

    @Test
    public void testWrongDestination() {
        assertThrows(MojoExecutionException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some", "data:,foo"),
                    concatenatedFile("some/file", "data:,bar")
            );
            mojo.execute();
        });
    }

    @Test
    public void testMalformedURI() {
        assertThrows(MojoFailureException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some", "data:foo")
            );
            mojo.execute();
        });
        assertThrows(MojoFailureException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some", "maven:g:a%20t:v")
            );
            mojo.addArtifact("g:a t:jar:v", new byte[0]);
            mojo.execute();
        });
        assertThrows(MojoFailureException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some", "maven:t:o:o:m:a:n:y:c:o:l:o:n:s")
            );
            mojo.addArtifact("t:o:o:m:a:n:y:c:o:l:o:n:s", new byte[0]);
            mojo.execute();
        });
    }

    @Test
    public void testUnsupportedScheme() {
        assertThrows(MojoFailureException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some", "not-supported:foo")
            );
            mojo.execute();
        });
    }

    @Test
    public void testIOFailure() {
        assertThrows(MojoExecutionException.class, () -> {
            MockConcatenateMojo mojo = new MockConcatenateMojo(
                    concatenatedFile("some", "maven:g:a:v!/file")
            );
            byte[] zip = createEmptyZip();
            zip = appendZip(zip, "file", new byte[0]);
            zip[6] &= 1; // Corrupt zip
            mojo.addArtifact("g:a:jar:v", zip);
            mojo.execute();
        });
    }


    private static final class MockConcatenateMojo extends ConcatenateMojo {
        private static final byte[] DIR_MARKER = new byte[0];
        private final Map<String, byte[]> files = new HashMap<>();
        private final Map<String, byte[]> artifacts = new HashMap<>();

        private MockConcatenateMojo(ConcatenatedFile... files) {
            this.outputDirectory = new File("dest").getAbsolutePath();
            this._projectDirectory = new File("source").getAbsoluteFile();
            super.files = Arrays.asList(files);
        }

        public void addSourceFile(String fileName, byte[] content) {
            files.put(this._projectDirectory.toPath().resolve(fileName).toAbsolutePath().toString(), content);
        }

        public void addDestFile(String fileName, byte[] content) {
            files.put(Paths.get(this.outputDirectory).resolve(fileName).toAbsolutePath().toString(), content);
        }

        public byte[] fetchDestFile(String fileName) {
            return files.remove(Paths.get(this.outputDirectory).resolve(fileName).toAbsolutePath().toString());
        }

        public void addArtifact(String coords, byte[] content) {
            artifacts.put(coords, content);
        }

        @Override
        protected boolean skip(Path out, boolean skipExisting) {
            return files.containsKey(out.toString()) && skipExisting;
        }

        @Override
        protected InputStream openSourceFile(Path source) throws IOException {
            byte[] content = this.files.get(source.toAbsolutePath().toString());
            if (content == null)
                throw new NoSuchFileException(source.toString());
            return new ByteArrayInputStream(content);
        }

        @Override
        protected OutputStream openDestFile(Path out, boolean append) throws IOException {
            if (files.get(out.getParent().toAbsolutePath().toString()) != DIR_MARKER)
                throw new IOException("Unable to create, since no parent dir exists");
            final String key = out.toAbsolutePath().toString();
            if (files.get(key) == DIR_MARKER)
                throw new IOException("Unable to create, since file is a directory!");
            ByteArrayOutputStream result = new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    files.put(key, toByteArray());
                }
            };
            if (append && files.get(key) != null)
                result.write(files.get(key));
            return result;
        }

        @Override
        protected void ensureDirectories(Path path) throws IOException {
            byte[] dir = files.putIfAbsent(path.getParent().toAbsolutePath().toString(), DIR_MARKER);
            if (dir != DIR_MARKER && dir != null)
                throw new IOException("Creating file within file");

        }

        @Override
        protected InputStream resolveArtifact(Artifact artifact) throws ArtifactResolutionException {
            byte[] content = this.artifacts.get(artifact.toString());
            if (content == null)
                throw new ArtifactResolutionException(Collections.emptyList(), "Not found " + artifact);
            return new ByteArrayInputStream(content);
        }

        public void assertNoFiles() {
            assertEquals(
                    Collections.emptyList(),
                    files.entrySet().stream()
                            .filter(e -> e.getValue() != DIR_MARKER)
                            .map(Map.Entry::getKey)
                            .filter(s -> s.startsWith(outputDirectory))
                            .collect(Collectors.toList())
            );
        }
    }

}
