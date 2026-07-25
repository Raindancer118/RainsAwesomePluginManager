package de.raindancer.apm.source;

import java.util.List;

import de.raindancer.apm.util.Downloader;

/**
 * A place APM can obtain plugin jars from.
 *
 * <p>Implementations block on network I/O and are only ever called from APM's worker thread.
 */
public interface PluginSource {

    /** Short identifier used in messages and in APM's install database. */
    String id();

    /** @return {@code true} if this source claims responsibility for the given query */
    boolean handles(String query);

    /**
     * Resolves a query to a concrete downloadable artefact.
     *
     * @param query          the user supplied query, e.g. a URL or {@code modrinth:luckperms}
     * @param serverVersion  the running server's Minecraft version, used to pick a matching build
     * @throws Downloader.DownloadException when nothing matching could be resolved
     */
    ResolvedDownload resolve(String query, String serverVersion) throws Downloader.DownloadException;

    /**
     * Searches the source's catalogue. Sources without a catalogue return an empty list.
     *
     * @param serverVersion the running server's Minecraft version, used to filter results
     */
    default List<SearchResult> search(String query, String serverVersion)
            throws Downloader.DownloadException {
        return List.of();
    }

    /**
     * A catalogue entry.
     *
     * @param slug        stable identifier usable with {@link #resolve}
     * @param title       display name
     * @param description short description
     * @param downloads   download count, or -1 when unknown
     */
    record SearchResult(String slug, String title, String description, long downloads) {
    }
}
