package io.solit.plugin.maven.cat;

import org.apache.commons.io.IOUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Maven goal used to concatenate resources identified by URI into a single output file.
 * <p>
 *     Currently following URI schemas supported:
 *     <dl>
 *         <dt><code>data:</code></dt>
 *         <dd>
 *             plain data from uri possibly compressed with base64, see
 *             <a href="https://en.wikipedia.org/wiki/Data_URI_scheme">wikipedia article</a>;
 *         </dd>
 *         <dt><code>file:</code></dt>
 *         <dd>
 *             files relative to project directory, schema may be omitted
 *         </dd>
 *         <dt><code>maven:</code></dt>
 *         <dd>
 *             link to a maven artifact or file inside a maven artifact (for jars and other zips) formatted as
 *             <code>maven:&lt;groupId&gt;:&lt;artifactId&gt;[:&lt;type&gt;[:&lt;classifier&gt;]]:&lt;version&gt;[!/&lt;path&gt;]</code>
 *         </dd>
 *     </dl>
 * @author yaga
 * @since 02.08.18
 */
@Mojo(name = "cat", defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class ConcatenateMojo extends AbstractMojo {
    private final static Pattern MAVEN_URI = Pattern.compile("^([^ !]+)(?:!/(.+))?$");

    @Component
    private ArtifactResolver _resolver;

    @Component
    private MavenSession _session;

    @Parameter(readonly = true, defaultValue = "${project}")
    private  MavenProject _project;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    File _projectDirectory;


    /**
     * Files to construct during execution. Each file include following parameters
     * <dl>
     *     <dt>file</dt> <dd>mandatory result file pathname relative to <code>outputDirectory</code></dd>
     *     <dt>parts</dt> <dd>list of URIs for resources used to build file content</dd>
     *     <dt>skipExisting</dt> <dd>boolean flag used to determine if file should be processed if it already exists</dd>
     *     <dt>append</dt> <dd>boolean flag used to determine if file should be appended if it already exists</dd>
     * </dl>
     */
    @Parameter
    List<ConcatenatedFile> files;

    /**
     * Directory to create files relative to
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    String outputDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (files == null) {
            getLog().info("[cat-maven-plugin]: No files are set, skipping");
            return;
        }
        if (outputDirectory == null || outputDirectory.isEmpty()) {
            throw new MojoFailureException("Output directory must be set");
        }
        Path outputDir = Paths.get(outputDirectory);
        getLog().info("[cat-maven-plugin]: Path outputDir: " + outputDir);
        getLog().info("[cat-maven-plugin]: Files " + files);
        for (ConcatenatedFile file: files) {
            try{
                getLog().info("[cat-maven-plugin]: File " + file);
                if (file.getOutputFile() == null || file.getOutputFile().isEmpty())
                    throw new MojoFailureException("File name is not provided");
                Path out = outputDir.resolve(file.getOutputFile());
                if (skip(out, file.isSkipExisting())) {
                    getLog().info("[cat-maven-plugin]: skipping " + file);
                    continue;
                }
                ensureDirectories(out);
                try (OutputStream output = openDestFile(out, file.isAppend())) {
                    if (file.getParts() == null || file.getParts().isEmpty()) {
                        getLog().info("[cat-maven-plugin]: parts are empty, skipping");
                        continue;
                    }
                    for (URI uri: file.getParts()) {
                        String scheme = uri.getScheme();
                        if (scheme == null)
                            scheme = "file";
                        InputStream input;
                        switch (scheme.toLowerCase()) {
                            case "file":
                                input = processFileURI(uri);
                                break;
                            case "data":
                                input = processDataURI(uri);
                                break;
                            case "maven":
                                input = processMavenURI(uri);
                                break;
                            default:
                                throw new MojoFailureException("Unsupported uri scheme '" + scheme + "'");
                        }
                        try {
                            IOUtils.copy(input, output);
                        } finally {
                            input.close();
                        }
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to create file", e);
            }
        }
    }

    protected boolean skip(Path out, boolean skipExisting) {
        return Files.exists(out) && skipExisting;
    }

    protected InputStream openSourceFile(Path source) throws IOException {
        return Files.newInputStream(source, StandardOpenOption.READ);
    }

    protected OutputStream openDestFile(Path out, boolean append) throws IOException {
        OpenOption[] options;
        if (append)
            options = new OpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE};
        else
            options = new OpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.CREATE};
        return Files.newOutputStream(out, options);
    }

    protected void ensureDirectories(Path path) throws IOException {
        Path dir = path.getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    protected InputStream resolveArtifact(Artifact artifact) throws ArtifactResolutionException, FileNotFoundException {
        InputStream artifactStream;
        getLog().info("[cat-maven-plugin]: resolving artifact " + artifact);
        ArtifactRequest request = new ArtifactRequest(
                artifact, _project.getRemoteProjectRepositories(), null
        );
        ArtifactResult artifactResult = _resolver.resolveArtifact(_session.getRepositorySession(), request);
        if (artifactResult.getArtifact().getFile() == null)
            throw new ArtifactResolutionException(
                    Collections.singletonList(artifactResult),
                    "Unable to resolve artifact file " + artifact.toString()
            );
        artifactStream = new FileInputStream(artifactResult.getArtifact().getFile());
        return artifactStream;
    }

    private InputStream processDataURI(URI uri) throws MojoFailureException {
        getLog().info("[cat-maven-plugin]: resolving uri " + uri);
        int sep = uri.getSchemeSpecificPart().indexOf(',');
        if (sep < 0)
            throw new MojoFailureException("Data uri should contain ',' separating actual data");
        String type = uri.getSchemeSpecificPart().substring(0, sep).trim();
        boolean base64 = type.endsWith(";base64");
        byte[] data = uri.getSchemeSpecificPart().substring(sep + 1).getBytes(StandardCharsets.UTF_8);
        InputStream is = new ByteArrayInputStream(data);
        if (base64)
            is = Base64.getMimeDecoder().wrap(is);
        return is;
    }

    private InputStream processFileURI(URI uri) throws IOException, MojoFailureException {
        try {
            getLog().info("[cat-maven-plugin]: resolving file " + uri);
            Path source = _projectDirectory.toPath().resolve(uri.getSchemeSpecificPart());
            return openSourceFile(source);
        } catch (NoSuchFileException e) {
            throw new MojoFailureException("Unable to read uri " + uri, e);
        }
    }

    private InputStream processMavenURI(URI uri) throws MojoFailureException, IOException {
        getLog().info("[cat-maven-plugin]: resolving maven uri " + uri);
        Matcher matcher = MAVEN_URI.matcher(uri.getSchemeSpecificPart());
        if (!matcher.matches())
            throw new MojoFailureException("Malformed maven uri: " + uri);
        InputStream artifactStream;
        try {
            artifactStream = resolveArtifact(new DefaultArtifact(matcher.group(1)));
        } catch (ArtifactResolutionException e) {
            throw new MojoFailureException("Unable to resolve artifact " + uri, e);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException("Malformed artifact URI " + uri, e);
        }
        if (matcher.group(2) == null || matcher.group(2).isEmpty())
            return artifactStream;
        ZipInputStream zis = new ZipInputStream(artifactStream);
        try {
            for (ZipEntry ze = zis.getNextEntry(); ze != null; ze = zis.getNextEntry())
                if (ze.getName().equals(matcher.group(2)))
                    return zis;
        } catch (Throwable t) {
            try {
                zis.close();
            } catch (Throwable e) {
                e.addSuppressed(t);
                throw e;
            }
            throw t;
        }
        zis.close();
        throw new MojoFailureException("Artifact '" + matcher.group(1) + "' does not contain '" + matcher.group(2) + "'");
    }

}
