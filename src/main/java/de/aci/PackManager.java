package de.aci;

import com.sun.net.httpserver.HttpServer;
import de.aci.gui.Ui;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Baut das Resource Pack (eingebaute Kristall-Texturen + eigene PNGs aus plugins/ACI/textures),
 * hostet es per Mini-Webserver und schickt es an die Spieler.
 */
public final class PackManager {

    public static final String NAMESPACE = "aci";
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("aci-resourcepack".getBytes(StandardCharsets.UTF_8));
    private static final List<String> BUILTIN = List.of(
            "orange_crystal", "yellow_crystal", "blue_crystal", "purple_crystal",
            "white_crystal", "gray_crystal", "black_crystal");
    private static final long FIXED_TIME = 315532800000L; // 1980-01-01, damit der SHA-1 stabil bleibt

    private final AciPlugin plugin;
    private final File textureDir;
    private final File zipFile;
    private volatile byte[] zipBytes = new byte[0];
    private volatile byte[] sha1 = new byte[20];
    private volatile List<String> names = List.of();
    private HttpServer server;
    private ExecutorService executor;

    public PackManager(AciPlugin plugin) {
        this.plugin = plugin;
        this.textureDir = new File(plugin.getDataFolder(), "textures");
        this.zipFile = new File(plugin.getDataFolder(), "resourcepack.zip");
    }

    public List<String> textureNames() {
        return names;
    }

    // ------------------------------------------------------------------ Pack bauen

    public void rebuild() {
        Map<String, byte[]> textures = new TreeMap<>();
        List<String> order = new ArrayList<>();

        for (String n : BUILTIN) {
            try (InputStream in = plugin.getResource("pack/textures/" + n + ".png")) {
                if (in != null) {
                    textures.put(n, in.readAllBytes());
                    order.add(n);
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Built-in texture " + n + " could not be read: " + ex.getMessage());
            }
        }

        File[] custom = textureDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (custom != null) {
            java.util.Arrays.sort(custom);
            for (File f : custom) {
                String n = f.getName().substring(0, f.getName().length() - 4)
                        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                try {
                    textures.put(n, Files.readAllBytes(f.toPath())); // ueberschreibt eingebaute gleichen Namens
                    if (!order.contains(n)) order.add(n);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Texture " + f.getName() + " could not be read: " + ex.getMessage());
                }
            }
        }

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                put(zip, "pack.mcmeta", ("""
                        {"pack":{"description":"ACI Custom Items","pack_format":46,\
                        "supported_formats":{"min_inclusive":46,"max_inclusive":999},\
                        "min_format":46,"max_format":999}}""").getBytes(StandardCharsets.UTF_8));
                for (String n : order) {
                    put(zip, "assets/aci/textures/item/" + n + ".png", textures.get(n));
                    put(zip, "assets/aci/models/item/" + n + ".json", ("{\"parent\":\"minecraft:item/generated\","
                            + "\"textures\":{\"layer0\":\"aci:item/" + n + "\"}}").getBytes(StandardCharsets.UTF_8));
                    put(zip, "assets/aci/items/" + n + ".json", ("{\"model\":{\"type\":\"minecraft:model\","
                            + "\"model\":\"aci:item/" + n + "\"}}").getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] bytes = bos.toByteArray();
            Files.write(zipFile.toPath(), bytes);
            this.sha1 = MessageDigest.getInstance("SHA-1").digest(bytes);
            this.zipBytes = bytes;
            this.names = List.copyOf(order);
            plugin.getLogger().info("Resource pack built: " + order.size() + " textures, SHA-1 " + HexFormat.of().formatHex(sha1));
        } catch (Exception ex) {
            plugin.getLogger().severe("Resource pack could not be built: " + ex.getMessage());
        }
    }

    private static void put(ZipOutputStream zip, String path, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(path);
        e.setTime(FIXED_TIME);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    // ------------------------------------------------------------------ Webserver

    public void start() {
        stop();
        var cfg = plugin.getConfig();
        if (!cfg.getString("resource-pack.url", "").isBlank()) return;
        if (!cfg.getBoolean("resource-pack.host.enabled", true)) return;
        int port = cfg.getInt("resource-pack.host.port", 8123);
        try {
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "ACI-PackHost");
                t.setDaemon(true);
                return t;
            });
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/aci-pack.zip", ex -> {
                byte[] b = zipBytes;
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            });
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Pack web server running on port " + port + " (open this port for TCP!)");
            if (cfg.getString("resource-pack.host.public-address", "").isBlank()
                    && plugin.getServer().getIp().isBlank()) {
                plugin.getLogger().warning("resource-pack.host.public-address in config.yml is empty - "
                        + "set your server's public IP/domain there, otherwise players cannot download the pack!");
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Pack web server could not start (port " + port + "): " + ex.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ------------------------------------------------------------------ Senden

    private String url() {
        var cfg = plugin.getConfig();
        String ext = cfg.getString("resource-pack.url", "");
        if (!ext.isBlank()) return ext;
        if (server == null) return null;
        String addr = cfg.getString("resource-pack.host.public-address", "");
        if (addr.isBlank()) addr = plugin.getServer().getIp();
        if (addr.isBlank()) addr = "127.0.0.1";
        int port = cfg.getInt("resource-pack.host.port", 8123);
        return "http://" + addr + ":" + port + "/aci-pack.zip?v=" + HexFormat.of().formatHex(sha1).substring(0, 8);
    }

    public void sendTo(Player p) {
        String url = url();
        if (url == null) return;
        var cfg = plugin.getConfig();
        byte[] hash = sha1;
        if (!cfg.getString("resource-pack.url", "").isBlank()) {
            String hex = cfg.getString("resource-pack.sha1", "").trim();
            hash = hex.length() == 40 ? HexFormat.of().parseHex(hex) : null;
        }
        p.setResourcePack(PACK_ID, url, hash,
                Ui.text(cfg.getString("resource-pack.prompt", "&6Bitte lade das ACI-Resource-Pack!")),
                cfg.getBoolean("resource-pack.required", false));
    }
}
