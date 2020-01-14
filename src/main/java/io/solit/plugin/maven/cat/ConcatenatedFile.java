package io.solit.plugin.maven.cat;

import java.net.URI;
import java.util.List;

/**
 * @author yaga
 * @since 02.08.18
 */
public class ConcatenatedFile {

    String file;

    List<URI> parts;

    boolean skipExisting = false;

    boolean append = false;

    public String getOutputFile() {
        return file;
    }

    public List<URI> getParts() {
        return parts;
    }

    public boolean isSkipExisting() {
        return skipExisting;
    }

    public boolean isAppend() {
        return append;
    }

    @Override
    public String toString() {
        return "ConcatenatedFile{" +
            "file='" + file + '\'' +
            ", parts=" + parts +
            ", skipExisting=" + skipExisting +
            ", append=" + append +
            '}';
    }
}
