package de.raindancer.apm.source;

import java.net.URI;
import java.util.Optional;

/**
 * A concrete artefact a {@link PluginSource} resolved a user query to.
 *
 * @param uri            direct download URI of the jar
 * @param fileName       the file name to store it under (already sanitised by the source)
 * @param sha512         publisher supplied SHA-512, when the source provides one
 * @param sourceId       identifier of the source, e.g. {@code url} or {@code modrinth}
 * @param projectId      source specific project identifier, used by {@code /apm update}
 * @param versionId      source specific version identifier
 * @param versionName    human readable version, e.g. {@code 2.20.1}
 * @param declaredGameVersions game versions the publisher declared, may be empty
 */
public record ResolvedDownload(URI uri,
                               String fileName,
                               Optional<String> sha512,
                               String sourceId,
                               Optional<String> projectId,
                               Optional<String> versionId,
                               Optional<String> versionName,
                               java.util.List<String> declaredGameVersions) {

    public ResolvedDownload {
        declaredGameVersions = declaredGameVersions == null
                ? java.util.List.of()
                : java.util.List.copyOf(declaredGameVersions);
    }
}
