package de.raindancer.apm.source;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import de.raindancer.apm.util.Downloader;
import de.raindancer.apm.util.SafeFileName;

/**
 * The source described in the original brief: hand APM a link to a jar and it installs it.
 *
 * <p>Nothing is known about the artefact up front — no checksum, no declared game versions —
 * so the compatibility decision is made later from the descriptor inside the downloaded jar.
 */
public final class DirectUrlSource implements PluginSource {

    private final Downloader downloader;

    public DirectUrlSource(Downloader downloader) {
        this.downloader = downloader;
    }

    @Override
    public String id() {
        return "url";
    }

    @Override
    public boolean handles(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    @Override
    public ResolvedDownload resolve(String query, String serverVersion)
            throws Downloader.DownloadException {
        URI uri = downloader.validate(query);
        String fileName = SafeFileName.fromUri(uri, "downloaded-plugin");
        return new ResolvedDownload(uri, fileName, Optional.empty(), id(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }
}
