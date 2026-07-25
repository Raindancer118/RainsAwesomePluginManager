package de.raindancer.apm.source;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import de.raindancer.apm.util.Downloader;
import de.raindancer.apm.util.SafeFileName;
import de.raindancer.apm.version.McVersion;

/**
 * Resolves plugins from <a href="https://modrinth.com">Modrinth</a>, which is what turns APM
 * from "download this link for me" into something that actually feels like {@code apt}.
 *
 * <p>Queries look like {@code modrinth:luckperms} or {@code modrinth:luckperms@v5.5.53-bukkit}.
 * A bare word that is not a URL is also treated as a Modrinth slug, so {@code /apm install
 * luckperms} does the obvious thing.
 *
 * <p>Version selection asks Modrinth for builds matching the configured loader and the running
 * server version, falling back from the exact patch version to {@code major.minor} — publishers
 * frequently tag only the latter.
 */
public final class ModrinthSource implements PluginSource {

    private static final String API = "https://api.modrinth.com/v2";
    private static final String PREFIX = "modrinth:";

    private final Downloader downloader;
    private final String loader;

    public ModrinthSource(Downloader downloader, String loader) {
        this.downloader = downloader;
        this.loader = loader == null || loader.isBlank() ? "paper" : loader.toLowerCase(Locale.ROOT);
    }

    @Override
    public String id() {
        return "modrinth";
    }

    @Override
    public boolean handles(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.startsWith(PREFIX)) {
            return true;
        }
        // Anything that is not a URL is treated as a slug.
        return !lower.startsWith("http://") && !lower.startsWith("https://");
    }

    @Override
    public ResolvedDownload resolve(String query, String serverVersion)
            throws Downloader.DownloadException {
        String spec = stripPrefix(query);
        String slug = spec;
        String pinnedVersion = null;
        int at = spec.lastIndexOf('@');
        if (at > 0) {
            slug = spec.substring(0, at);
            pinnedVersion = spec.substring(at + 1);
        }
        validateSlug(slug);

        JsonArray versions = fetchVersions(slug, serverVersion, pinnedVersion != null);
        if (versions.isEmpty()) {
            throw new Downloader.DownloadException("Modrinth has no '" + loader
                    + "' build of '" + slug + "' for Minecraft " + serverVersion
                    + ". Use /apm search " + slug + " to check what exists, or install a direct "
                    + "jar URL with /apm install <url> if you know what you are doing.");
        }

        JsonObject version = pinnedVersion == null
                ? newest(versions)
                : pinned(versions, pinnedVersion, slug);

        JsonObject file = primaryFile(version, slug);
        String rawUrl = string(file, "url");
        if (rawUrl == null) {
            throw new Downloader.DownloadException(
                    "Modrinth returned a version of '" + slug + "' without a download URL.");
        }

        URI uri = downloader.validate(rawUrl);
        String fileName = SafeFileName.fromName(string(file, "filename"), slug);
        Optional<String> sha512 = Optional.ofNullable(file.getAsJsonObject("hashes"))
                .map(hashes -> string(hashes, "sha512"));

        return new ResolvedDownload(
                uri,
                fileName,
                sha512,
                id(),
                Optional.ofNullable(string(version, "project_id")),
                Optional.ofNullable(string(version, "id")),
                Optional.ofNullable(string(version, "version_number")),
                stringList(version.getAsJsonArray("game_versions")));
    }

    @Override
    public List<SearchResult> search(String query, String serverVersion)
            throws Downloader.DownloadException {
        String facets = "[[\"project_type:plugin\"],[\"categories:" + loader + "\"]]";
        String url = API + "/search?limit=10&index=relevance"
                + "&query=" + encode(query)
                + "&facets=" + encode(facets);

        JsonObject root = parseObject(downloader.getString(URI.create(url)), "search results");
        JsonArray hits = root.getAsJsonArray("hits");
        if (hits == null) {
            return List.of();
        }

        List<SearchResult> results = new ArrayList<>(hits.size());
        for (JsonElement element : hits) {
            if (!(element instanceof JsonObject hit)) {
                continue;
            }
            String slug = string(hit, "slug");
            if (slug == null) {
                continue;
            }
            results.add(new SearchResult(
                    slug,
                    Optional.ofNullable(string(hit, "title")).orElse(slug),
                    Optional.ofNullable(string(hit, "description")).orElse(""),
                    hit.has("downloads") && hit.get("downloads").isJsonPrimitive()
                            ? hit.get("downloads").getAsLong() : -1L));
        }
        return results;
    }

    /**
     * Asks Modrinth for versions, narrowing by game version unless the caller pinned an explicit
     * version (in which case the pin wins over compatibility filtering).
     */
    private JsonArray fetchVersions(String slug, String serverVersion, boolean pinned)
            throws Downloader.DownloadException {
        List<String> gameVersionCandidates = new ArrayList<>();
        if (pinned) {
            // An explicit pin means the operator has already made the compatibility call;
            // the jar's own api-version is still checked before anything is installed.
            gameVersionCandidates.add(null);
        } else {
            gameVersionCandidates.add(serverVersion);
            McVersion.parse(serverVersion)
                    .map(McVersion::majorMinor)
                    .filter(majorMinor -> !majorMinor.equals(serverVersion))
                    .ifPresent(gameVersionCandidates::add);
        }

        for (String gameVersion : gameVersionCandidates) {
            StringBuilder url = new StringBuilder(API + "/project/" + encode(slug) + "/version");
            url.append("?loaders=").append(encode("[\"" + loader + "\"]"));
            if (gameVersion != null) {
                url.append("&game_versions=").append(encode("[\"" + gameVersion + "\"]"));
            }
            JsonArray versions = parseArray(downloader.getString(URI.create(url.toString())),
                    "version list for " + slug);
            if (!versions.isEmpty()) {
                return versions;
            }
        }
        return new JsonArray();
    }

    /** Modrinth returns versions newest first, but prefer a stable release over a beta/alpha. */
    private static JsonObject newest(JsonArray versions) {
        for (JsonElement element : versions) {
            if (element instanceof JsonObject version
                    && "release".equals(string(version, "version_type"))) {
                return version;
            }
        }
        return versions.get(0).getAsJsonObject();
    }

    private static JsonObject pinned(JsonArray versions, String wanted, String slug)
            throws Downloader.DownloadException {
        for (JsonElement element : versions) {
            if (!(element instanceof JsonObject version)) {
                continue;
            }
            if (wanted.equalsIgnoreCase(string(version, "version_number"))
                    || wanted.equalsIgnoreCase(string(version, "id"))) {
                return version;
            }
        }
        throw new Downloader.DownloadException("'" + slug + "' has no version '" + wanted + "'.");
    }

    private static JsonObject primaryFile(JsonObject version, String slug)
            throws Downloader.DownloadException {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            throw new Downloader.DownloadException(
                    "Modrinth returned a version of '" + slug + "' with no files attached.");
        }
        for (JsonElement element : files) {
            if (element instanceof JsonObject file
                    && file.has("primary") && file.get("primary").getAsBoolean()) {
                return file;
            }
        }
        return files.get(0).getAsJsonObject();
    }

    private static void validateSlug(String slug) throws Downloader.DownloadException {
        if (slug.isBlank() || !slug.matches("[A-Za-z0-9!@$()`.+,\"\\-']{1,64}")) {
            throw new Downloader.DownloadException(
                    "'" + slug + "' is not a valid Modrinth project slug or id.");
        }
    }

    private static String stripPrefix(String query) {
        return query.toLowerCase(Locale.ROOT).startsWith(PREFIX)
                ? query.substring(PREFIX.length()).trim()
                : query.trim();
    }

    private static JsonObject parseObject(String json, String what) throws Downloader.DownloadException {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new Downloader.DownloadException("Modrinth returned unexpected " + what + ".");
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new Downloader.DownloadException("Modrinth returned malformed " + what + ".", e);
        }
    }

    private static JsonArray parseArray(String json, String what) throws Downloader.DownloadException {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                throw new Downloader.DownloadException("Modrinth returned unexpected " + what + ".");
            }
            return parsed.getAsJsonArray();
        } catch (JsonSyntaxException e) {
            throw new Downloader.DownloadException("Modrinth returned malformed " + what + ".", e);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static List<String> stringList(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
