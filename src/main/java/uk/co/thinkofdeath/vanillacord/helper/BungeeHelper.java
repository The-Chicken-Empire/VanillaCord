package uk.co.thinkofdeath.vanillacord.helper;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.io.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("ConstantConditions")
public class BungeeHelper {

    private static final Gson GSON = new Gson();
    static final AttributeKey<UUID> UUID_KEY = NetworkManager.getAttribute("-vch-uuid");
    static final AttributeKey<Property[]> PROPERTIES_KEY = NetworkManager.getAttribute("-vch-properties");

    public static void parseHandshake(Object network, Object handshake) {
        try {
            Channel channel = (Channel) NetworkManager.channel.get(network);
            String uuid, host = Handshake.getHostName(handshake);
            String[] split = host.split("\00", 5);
            if ((split.length != 4 && split.length != 3) || (uuid = split[2]).length() != 32) {
                throw QuietException.show("If you wish to use IP forwarding, please enable it in your BungeeCord config as well!");
            }

        //  split[0]; // we don't do anything with the server address
            NetworkManager.socket.set(network, new InetSocketAddress(split[1], ((InetSocketAddress) NetworkManager.socket.get(network)).getPort()));
            channel.attr(UUID_KEY).set(UUID.fromString(
                    new StringBuilder(36).append(uuid, 0,  8)
                            .append('-').append(uuid,  8, 12)
                            .append('-').append(uuid, 12, 16)
                            .append('-').append(uuid, 16, 20)
                            .append('-').append(uuid, 20, 32)
                            .toString()
            ));

            if (getSeecret().length == 0) {
                channel.attr(PROPERTIES_KEY).set((split.length == 3)? new Property[0] : GSON.fromJson(split[3], Property[].class));
                return;
            } else if (split.length == 4) {
                Property[] properties = GSON.fromJson(split[3], Property[].class);
                if (properties.length != 0) {
                    Property[] modified = new Property[properties.length - 1];
                    channel.attr(PROPERTIES_KEY).set(modified);

                    int i = 0;
                    boolean found = false;
                    for (Property property : properties) {
                        if ("bungeeguard-token".equals(property.getName())) {
                            if (!(found = !found && Arrays.binarySearch(seecret, property.getValue()) >= 0)) {
                                break;
                            }
                        } else if (i != modified.length) {
                            modified[i++] = property;
                        }
                    }
                    if (found) {
                        return;
                    }
                }
            }
            throw QuietException.show("Received invalid IP forwarding data. Did you use the right forwarding secret?");
        } catch (Exception e) {
            throw exception(null, e);
        }
    }

    private static String[] seecret = null;
    private static String[] getSeecret() throws IOException {
        if (seecret == null) {
            File config = new File("seecret.txt");
            if (config.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(config)))) {
                    HashSet<String> tokens = new HashSet<>();
                    for (String line; (line = reader.readLine()) != null;) {
                        if (line.startsWith("bungeeguard-token=") && (line = line.substring(18)).length() != 0) {
                            tokens.add(line);
                        }
                    }
                    Arrays.sort(seecret = (tokens.size() != 0)? tokens.toArray(new String[0]) : new String[0]);
                }
            } else {
                seecret = new String[0];
                PrintWriter writer = new PrintWriter(config, UTF_8.name());
                writer.println("# As you may know, standard IP forwarding procedure is to accept connections from any and all proxies.");
                writer.println("# If you'd like to change that, you can do so by entering BungeeGuard tokens here.");
                writer.println("# ");
                writer.println("# This file is automatically generated by VanillaCord once a player attempts to join the server.");
                writer.println();
                writer.println("bungeeguard-token=");
                writer.close();
            }
        }
        return seecret;
    }

    public static GameProfile injectProfile(Object network, GameProfile profile) {
        try {
            Channel channel = (Channel) NetworkManager.channel.get(network);

            GameProfile modified = new GameProfile(channel.attr(UUID_KEY).get(), profile.getName());
            for (Property property : channel.attr(PROPERTIES_KEY).get()) {
                modified.getProperties().put(property.getName(), property);
            }

            return modified;
        } catch (Exception e) {
            throw exception(null, e);
        }
    }

    static RuntimeException exception(String text, Throwable e) {
        if (e instanceof QuietException) {
            return (QuietException) e;
        } else if (e.getCause() instanceof QuietException) {
            return (QuietException) e.getCause();
        } else {
            if (text != null) e = new RuntimeException(text, e);
            e.printStackTrace();
            return new QuietException(e);
        }
    }

    // Pre-calculate references to obfuscated classes
    static final class NetworkManager {
        public static final Field channel;
        public static final Field socket;

        static {
            try {
                Class<?> clazz = (Class<?>) (Object) "VCTR-NetworkManager";

                channel = clazz.getDeclaredField("VCFR-NetworkManager-Channel");
                channel.setAccessible(true);

                socket = clazz.getDeclaredField("VCFR-NetworkManager-Socket");
                socket.setAccessible(true);
            } catch (Throwable e) {
                throw exception("Class generation failed", e);
            }
        }

        // this isn't obfuscated, but was changed in 1.12
        public static <T> AttributeKey<T> getAttribute(String key) {
            return AttributeKey.valueOf(key);
        }
    }
    static final class Handshake {
        private static final Field hostName;

        static {
            try {
                Class<?> clazz = (Class<?>) (Object) "VCTR-HandshakePacket";

                hostName = clazz.getDeclaredField("VCFR-HandshakePacket-HostName");
                hostName.setAccessible(true);
            } catch (Throwable e) {
                throw exception("Class generation failed", e);
            }
        }

        public static String getHostName(Object instance) throws Exception {
            return (String) Handshake.hostName.get(instance);
        }
    }
}
