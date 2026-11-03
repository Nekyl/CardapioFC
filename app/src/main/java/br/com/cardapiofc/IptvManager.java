package br.com.cardapiofc;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Gerencia apenas a lista IPTV do usuário; não contém servidores ou playlists próprias. */
public final class IptvManager {
    private static final String KEY_ALIAS = "cardapiofc_iptv_credentials";
    private static final String PREFS = "iptv_secure_store";
    private static final String DATA_KEY = "payload";
    private static final String PREFETCH_KEY = "last_daily_prefetch";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 20000;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private volatile State state = State.empty();
    private volatile Map<String, List<Channel>> channelIndex = Collections.emptyMap();
    private volatile List<Channel> fixtureChannels = Collections.emptyList();
    private final LinkedHashMap<String, String> searchResultCache = new LinkedHashMap<>(32, 0.75f, true);

    public IptvManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        restore();
    }

    public synchronized String statusJson() {
        return state.toJson().toString();
    }

    public synchronized String channelsJson() {
        JSONArray result = new JSONArray();
        for (Channel channel : state.channels) result.put(channel.toJson());
        return result.toString();
    }

    public void connectXtream(String server, String username, String password) {
        connectXtream(server, username, password, true);
    }

    private void connectXtream(String server, String username, String password, boolean showLoading) {
        final String cleanServer = normalizeServer(server);
        final String user = username == null ? "" : username.trim();
        final String pass = password == null ? "" : password;
        if (showLoading) setLoading();
        executor.execute(() -> {
            try {
                if (cleanServer.isEmpty() || user.isEmpty() || pass.isEmpty()) throw new IOException("Preencha servidor, usuário e senha.");
                String query = "username=" + enc(user) + "&password=" + enc(pass);
                JSONObject account = requestJson(cleanServer + "/player_api.php?" + query);
                JSONObject info = account.optJSONObject("user_info");
                if (info == null) throw new IOException("Resposta Xtream inválida.");
                String auth = info.optString("auth", "1");
                if ("0".equals(auth)) throw new IOException("Usuário ou senha rejeitados.");
                JSONArray streams = requestArray(cleanServer + "/player_api.php?action=get_live_streams&" + query);
                List<Channel> channels = parseXtream(streams, cleanServer, user, pass);
                State next = new State("xtream", cleanServer, user, pass, "", channels,
                        info.optString("exp_date", ""), info.optInt("active_cons", -1), info.optInt("max_connections", -1), "connected", "");
                save(next);
            } catch (Throwable error) {
                fail(error.getMessage());
            }
        });
    }

    public void connectM3u(String playlistUrl) {
        connectM3u(playlistUrl, true);
    }

    private void connectM3u(String playlistUrl, boolean showLoading) {
        final String url = playlistUrl == null ? "" : playlistUrl.trim();
        String[] xtream = xtreamCredentialsFromPlaylist(url);
        if (xtream != null) {
            // get.php?username=...&password=... é a playlist Xtream; consulte a API
            // JSON em vez de baixar a playlist M3U inteira e estourar o heap do Android.
            connectXtream(xtream[0], xtream[1], xtream[2], showLoading);
            return;
        }
        if (showLoading) setLoading();
        executor.execute(() -> {
            try {
                if (url.isEmpty()) throw new IOException("Informe o link M3U.");
                List<Channel> channels = parseM3uUrl(url);
                if (channels.isEmpty()) throw new IOException("Nenhum canal foi encontrado na playlist.");
                save(new State("m3u", "", "", "", url, channels, "", -1, -1, "connected", ""));
            } catch (Throwable error) {
                fail(error.getMessage());
            }
        });
    }

    public void refresh() {
        State current = state;
        if ("m3u".equals(current.type)) connectM3u(current.playlist);
        else if ("xtream".equals(current.type)) connectXtream(current.server, current.username, current.password);
    }

    /** Atualiza a lista uma vez por dia, sem limpar os canais já cacheados nem alterar a UI. */
    public void warmupDaily() {
        State current = state;
        if (!"connected".equals(current.status) || todayKey().equals(prefs.getString(PREFETCH_KEY, ""))) return;
        if ("m3u".equals(current.type)) connectM3u(current.playlist, false);
        else if ("xtream".equals(current.type)) connectXtream(current.server, current.username, current.password, false);
    }

    public synchronized void disconnect() {
        state = State.empty();
        clearSearchData();
        prefs.edit().remove(DATA_KEY).remove(PREFETCH_KEY).apply();
    }

    public void shutdown() { executor.shutdownNow(); }

    private synchronized void setLoading() {
        state = new State("", "", "", "", "", Collections.emptyList(), "", -1, -1, "loading", "");
        clearSearchData();
    }

    private synchronized void fail(String message) {
        state = new State("", "", "", "", "", Collections.emptyList(), "", -1, -1, "error", message == null ? "Falha ao carregar a lista." : message);
        clearSearchData();
    }

    private synchronized void save(State next) {
        state = next;
        rebuildSearchIndex();
        try {
            SharedPreferences.Editor editor = prefs.edit().putString(DATA_KEY, encrypt(next.toStorageJson().toString()));
            if ("connected".equals(next.status)) editor.putString(PREFETCH_KEY, todayKey());
            editor.apply();
        }
        catch (Exception ignored) { /* a lista continua disponível nesta sessão */ }
    }

    private static String todayKey() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
        return format.format(new Date());
    }

    private void restore() {
        String encoded = prefs.getString(DATA_KEY, "");
        if (encoded.isEmpty()) return;
        try { state = State.fromJson(new JSONObject(decrypt(encoded))); rebuildSearchIndex(); }
        catch (Exception ignored) { state = State.empty(); clearSearchData(); prefs.edit().remove(DATA_KEY).apply(); }
    }

    public static boolean matches(String displayName, String playlistName) {
        return matchesNormalized(normalizeChannelName(displayName), normalizeChannelName(playlistName));
    }

    private static boolean matchesNormalized(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (isNamedChannel(a, "PREMIERE") && isNamedChannel(b, "PREMIERE")) return matchesPremiere(a, b);
        if (isNamedChannel(a, "PARAMOUNT") && isNamedChannel(b, "PARAMOUNT")) return matchesParamount(a, b);
        if (isNamedChannel(a, "GLOBO") && isNamedChannel(b, "GLOBO")) return matchesGlobo(a, b);
        return a.equals(b);
    }

    private static boolean isNamedChannel(String value, String name) { return value.equals(name) || value.startsWith(name + " "); }

    private static String stripBroadcastPrefix(String value) {
        if (value.startsWith("REDE ")) return value.substring("REDE ".length()).trim();
        if (value.startsWith("TV ")) return value.substring("TV ".length()).trim();
        return value;
    }

    private static String normalizeChannelName(String value) { return stripBroadcastPrefix(canonical(value)); }

    public synchronized String findStreamJson(String channelName) {
        JSONArray matches = findStreams(normalizeChannelName(channelName));
        return matches.length() == 0 ? "" : matches.optJSONObject(0).toString();
    }

    public synchronized String findStreamsJson(String channelName) {
        String query = normalizeChannelName(channelName);
        String key = "CHANNEL:" + query;
        String cached = searchResultCache.get(key);
        if (cached != null) return cached;
        return cacheSearchResult(key, findStreams(query).toString());
    }

    public synchronized String findSuggestedStreamsJson(String homeName, String awayName) {
        String home = normalizeTeamName(homeName), away = normalizeTeamName(awayName);
        if (home.isEmpty() && away.isEmpty()) return "[]";
        String key = "FIXTURE:" + home + "|" + away;
        String cached = searchResultCache.get(key);
        if (cached != null) return cached;
        Map<String, Channel> found = new LinkedHashMap<>();
        for (Channel channel : fixtureChannels) {
            if (fixtureScore(channel.searchName, home, away) > 0 && !found.containsKey(channel.url)) found.put(channel.url, channel);
        }
        List<Channel> ordered = new ArrayList<>(found.values());
        ordered.sort(Comparator.<Channel>comparingInt(channel -> fixtureScore(channel.searchName, home, away)).reversed()
                .thenComparing(channel -> channel.name.toLowerCase(Locale.ROOT)));
        JSONArray result = new JSONArray();
        for (Channel channel : ordered) {
            JSONObject item = channel.toJson();
            try { item.put("suggested", true); } catch (Exception ignored) { }
            result.put(item);
        }
        return cacheSearchResult(key, result.toString());
    }

    private JSONArray findStreams(String query) {
        JSONArray result = new JSONArray();
        if (query.isEmpty()) return result;
        Map<String, List<Channel>> index = channelIndex;
        List<Channel> candidates = new ArrayList<>();
        boolean familySearch = isNamedChannel(query, "PREMIERE") || isNamedChannel(query, "PARAMOUNT") || isNamedChannel(query, "GLOBO");
        if (familySearch) {
            for (Map.Entry<String, List<Channel>> entry : index.entrySet()) {
                if (matchesNormalized(query, entry.getKey())) candidates.addAll(entry.getValue());
            }
        } else {
            List<Channel> exact = index.get(query);
            if (exact != null) candidates.addAll(exact);
        }
        for (Channel channel : candidates) {
            if (matchesNormalized(query, channel.searchName)) result.put(channel.toJson());
        }
        return result;
    }

    private synchronized String cacheSearchResult(String key, String result) {
        searchResultCache.put(key, result);
        while (searchResultCache.size() > 300) searchResultCache.remove(searchResultCache.keySet().iterator().next());
        return result;
    }

    private synchronized void clearSearchData() {
        searchResultCache.clear();
        channelIndex = Collections.emptyMap();
        fixtureChannels = Collections.emptyList();
    }

    private synchronized void rebuildSearchIndex() {
        Map<String, List<Channel>> index = new LinkedHashMap<>();
        List<Channel> fixtures = new ArrayList<>();
        for (Channel channel : state.channels) {
            index.computeIfAbsent(channel.searchName, ignored -> new ArrayList<>()).add(channel);
            if (isFixtureChannelName(channel.searchName)) fixtures.add(channel);
        }
        for (Map.Entry<String, List<Channel>> entry : index.entrySet()) entry.setValue(Collections.unmodifiableList(entry.getValue()));
        channelIndex = Collections.unmodifiableMap(index);
        fixtureChannels = Collections.unmodifiableList(fixtures);
        searchResultCache.clear();
    }

    private static boolean isFixtureChannelName(String name) {
        String padded = " " + name + " ";
        return hasToken(padded, "X") || hasToken(padded, "VS") || hasToken(padded, "VERSUS");
    }

    private static String normalizeTeamName(String value) {
        String normalized = normalizeChannelName(value);
        return normalized.length() < 3 ? "" : normalized;
    }

    private static int fixtureScore(String channelName, String home, String away) {
        if (!isFixtureChannelName(channelName)) return 0;
        boolean homeMatch = matchesTeamPhrase(channelName, home);
        boolean awayMatch = matchesTeamPhrase(channelName, away);
        return (homeMatch ? 1 : 0) + (awayMatch ? 1 : 0);
    }

    private static boolean matchesTeamPhrase(String channelName, String team) {
        if (team.isEmpty()) return false;
        String paddedName = " " + channelName + " ";
        if (paddedName.contains(" " + team + " ")) return true;
        for (String word : team.split(" ")) {
            if (word.length() >= 5 && paddedName.contains(" " + word + " ")) return true;
        }
        return false;
    }

    private static String canonical(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ").trim().replaceAll("\\s+", " ");
        return normalized.replaceAll("\\b(FULL HD|FHD|ULTRA HD|UHD|4K|8K|HD|SD|1080P|720P|H264|H265|HEVC)\\b", " ")
                .trim().replaceAll("\\s+", " ");
    }

    private static boolean matchesPremiere(String requested, String available) {
        String requestedVariant = requested.substring("PREMIERE".length()).trim();
        String availableVariant = available.substring("PREMIERE".length()).trim();
        if (isPremiereFc(requestedVariant)) {
            return isPremiereFc(availableVariant) || "1".equals(availableVariant)
                    || "CLUBE".equals(availableVariant) || "CLUBES".equals(availableVariant);
        }
        if (requestedVariant.isEmpty()) return availableVariant.isEmpty() || "1".equals(availableVariant);
        if (requestedVariant.matches("\\d+")) return requestedVariant.equals(availableVariant);
        if ("CLUBE".equals(requestedVariant) || "CLUBES".equals(requestedVariant)) {
            return "CLUBE".equals(availableVariant) || "CLUBES".equals(availableVariant);
        }
        return requestedVariant.equals(availableVariant);
    }

    private static boolean isPremiereFc(String variant) {
        return "FC".equals(variant) || "F C".equals(variant)
                || "FUTEBOL CLUBE".equals(variant) || "FOOTBALL CLUB".equals(variant);
    }

    private static boolean matchesParamount(String requested, String available) {
        String requestedVariant = requested.substring("PARAMOUNT".length()).trim();
        String availableVariant = available.substring("PARAMOUNT".length()).trim();
        if (requestedVariant.isEmpty()) return availableVariant.isEmpty() || availableVariant.matches("\\d+");
        if (requestedVariant.matches("\\d+")) return requestedVariant.equals(availableVariant);
        return requestedVariant.equals(availableVariant);
    }

    private static boolean matchesGlobo(String requested, String available) {
        String requestedRegion = globoRegion(requested);
        String availableRegion = globoRegion(available);
        if (requestedRegion != null) return requestedRegion.equals(availableRegion);
        return "SP".equals(availableRegion) || "MG".equals(availableRegion) || "RJ".equals(availableRegion);
    }

    private static String globoRegion(String value) {
        String padded = " " + value + " ";
        if (hasToken(padded, "SP") || value.contains("SAO PAULO")) return "SP";
        if (hasToken(padded, "MG") || hasToken(padded, "MINAS") || value.contains("MINAS GERAIS")
                || hasToken(padded, "BH") || value.contains("BELO HORIZONTE")) return "MG";
        if (hasToken(padded, "RJ") || value.contains("RIO DE JANEIRO")) return "RJ";
        if (hasToken(padded, "DF") || value.contains("DISTRITO FEDERAL") || hasToken(padded, "BRASILIA")) return "DF";
        if (hasToken(padded, "RS") || value.contains("RIO GRANDE DO SUL")) return "RS";
        if (hasToken(padded, "PR") || value.contains("PARANA")) return "PR";
        if (hasToken(padded, "SC") || value.contains("SANTA CATARINA")) return "SC";
        if (hasToken(padded, "BA") || hasToken(padded, "BAHIA")) return "BA";
        if (hasToken(padded, "PE") || value.contains("PERNAMBUCO")) return "PE";
        if (hasToken(padded, "CE") || hasToken(padded, "CEARA")) return "CE";
        if (hasToken(padded, "GO") || hasToken(padded, "GOIAS")) return "GO";
        if (hasToken(padded, "ES") || value.contains("ESPIRITO SANTO")) return "ES";
        if (hasToken(padded, "AM") || value.contains("AMAZONAS")) return "AM";
        if (hasToken(padded, "PA") || hasToken(padded, "PARA")) return "PA";
        if (hasToken(padded, "MA") || value.contains("MARANHAO")) return "MA";
        if (hasToken(padded, "MS") || value.contains("MATO GROSSO DO SUL")) return "MS";
        if (hasToken(padded, "MT") || value.contains("MATO GROSSO")) return "MT";
        if (hasToken(padded, "RN") || value.contains("RIO GRANDE DO NORTE")) return "RN";
        if (hasToken(padded, "PB") || value.contains("PARAIBA")) return "PB";
        if (hasToken(padded, "AL") || value.contains("ALAGOAS")) return "AL";
        if (hasToken(padded, "SE") || value.contains("SERGIPE")) return "SE";
        if (hasToken(padded, "PI") || value.contains("PIAUI")) return "PI";
        if (hasToken(padded, "TO") || value.contains("TOCANTINS")) return "TO";
        if (hasToken(padded, "RO") || value.contains("RONDONIA")) return "RO";
        if (hasToken(padded, "AC") || value.contains("ACRE")) return "AC";
        if (hasToken(padded, "AP") || value.contains("AMAPA")) return "AP";
        if (hasToken(padded, "RR") || value.contains("RORAIMA")) return "RR";
        if (hasToken(padded, "NE") || value.contains("NORDESTE")) return "NE";
        return null;
    }

    private static boolean hasToken(String padded, String token) { return padded.contains(" " + token + " "); }
    private static String normalizeServer(String server) {
        String value = server == null ? "" : server.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.isEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        return value;
    }

    /** Converte o formato de playlist Xtream get.php no mesmo fluxo JSON do login Xtream. */
    private static String[] xtreamCredentialsFromPlaylist(String playlistUrl) {
        if (playlistUrl == null || playlistUrl.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(playlistUrl);
            String path = uri.getPath();
            String username = uri.getQueryParameter("username");
            String password = uri.getQueryParameter("password");
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (path == null || !path.toLowerCase(Locale.ROOT).endsWith("get.php")
                    || scheme == null || authority == null || authority.isEmpty()
                    || username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
                return null;
            }
            return new String[]{scheme + "://" + authority, username.trim(), password};
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

    private static JSONObject requestJson(String url) throws Exception {
        String text = requestText(url);
        return new JSONObject(text);
    }

    private static JSONArray requestArray(String url) throws Exception {
        String text = requestText(url);
        return new JSONArray(text);
    }

    private static String requestText(String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS); connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", "CardapioFC/4.1 Android");
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) throw new IOException("Servidor sem resposta (HTTP " + code + ").");
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) result.append(line).append('\n');
        } finally { connection.disconnect(); }
        if (code >= 400) throw new IOException("Servidor respondeu HTTP " + code + ".");
        return result.toString();
    }

    private static List<Channel> parseXtream(JSONArray array, String server, String user, String pass) {
        List<Channel> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i); if (item == null) continue;
            String name = item.optString("name", "").trim(); String id = item.optString("stream_id", "");
            if (name.isEmpty() || id.isEmpty()) continue;
            String extension = item.optString("container_extension", "m3u8");
            String url = server + "/live/" + encSafe(user) + "/" + encSafe(pass) + "/" + id + "." + (extension.isEmpty() ? "m3u8" : extension);
            result.add(new Channel(name, url, item.optString("stream_icon", ""), id));
        }
        return result;
    }

    private static String encSafe(String value) { try { return enc(value); } catch (Exception e) { return value; } }

    /** Lê playlists grandes em streaming: evita manter o conteúdo bruto e um split gigante na memória. */
    private static List<Channel> parseM3uUrl(String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "CardapioFC/4.1 Android");
        int code = connection.getResponseCode();
        InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) throw new IOException("Servidor sem resposta (HTTP " + code + ").");
        if (code >= 400) {
            try (InputStream ignored = input) { /* fecha o corpo antes de informar o erro */ }
            finally { connection.disconnect(); }
            throw new IOException("Servidor respondeu HTTP " + code + ".");
        }
        List<Channel> result = new ArrayList<>();
        String pendingName = "", pendingLogo = "", pendingId = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 8192)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.startsWith("#EXTINF")) {
                    int comma = line.indexOf(',');
                    pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "";
                    pendingLogo = attr(line, "tvg-logo");
                    pendingId = attr(line, "tvg-id");
                } else if (!line.isEmpty() && !line.startsWith("#") && !pendingName.isEmpty()) {
                    result.add(new Channel(pendingName, line, pendingLogo, pendingId));
                    pendingName = ""; pendingLogo = ""; pendingId = "";
                }
            }
        } finally {
            connection.disconnect();
        }
        return result;
    }

    private static String attr(String line, String key) {
        String marker = key + "=\""; int start = line.indexOf(marker); if (start < 0) return "";
        start += marker.length(); int end = line.indexOf('"', start); return end > start ? line.substring(start, end) : "";
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    private static String encrypt(String text) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    private static String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static final class Channel {
        final String name, url, logo, id, searchName;
        Channel(String name, String url, String logo, String id) { this.name = name; this.url = url; this.logo = logo; this.id = id; this.searchName = normalizeChannelName(name); }
        JSONObject toJson() { JSONObject o = new JSONObject(); try { o.put("name", name); o.put("url", url); o.put("logo", logo); o.put("id", id); } catch (Exception ignored) {} return o; }
        static Channel fromJson(JSONObject o) { return new Channel(o.optString("name"), o.optString("url"), o.optString("logo"), o.optString("id")); }
    }

    private static final class State {
        final String type, server, username, password, playlist, expiry, status, error; final List<Channel> channels; final int active, max;
        State(String type, String server, String username, String password, String playlist, List<Channel> channels, String expiry, int active, int max, String status, String error) { this.type=type;this.server=server;this.username=username;this.password=password;this.playlist=playlist;this.channels=channels;this.expiry=expiry;this.active=active;this.max=max;this.status=status;this.error=error; }
        static State empty() { return new State("", "", "", "", "", Collections.emptyList(), "", -1, -1, "disconnected", ""); }
        JSONObject toJson() { return makeJson(false); }
        JSONObject toStorageJson() { return makeJson(true); }
        JSONObject makeJson(boolean secret) { JSONObject o=new JSONObject(); try { o.put("type",type);o.put("server",server);o.put("username",username);if(secret)o.put("password",password);o.put("playlist",playlist);o.put("expiry",expiry);o.put("active",active);o.put("max",max);o.put("status",status);o.put("error",error); JSONArray a=new JSONArray();for(Channel c:channels)a.put(c.toJson());o.put("channels",a); } catch(Exception ignored){} return o; }
        static State fromJson(JSONObject o) { List<Channel> list=new ArrayList<>();JSONArray a=o.optJSONArray("channels");if(a!=null)for(int i=0;i<a.length();i++)list.add(Channel.fromJson(a.optJSONObject(i)));return new State(o.optString("type"),o.optString("server"),o.optString("username"),o.optString("password"),o.optString("playlist"),list,o.optString("expiry"),o.optInt("active",-1),o.optInt("max",-1),o.optString("status","disconnected"),o.optString("error")); }
    }
}
