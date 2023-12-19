package vanillacord.server;

import bridge.Invocation;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import vanillacord.translation.HandshakePacket;
import vanillacord.translation.PlayerConnection;

import java.util.Arrays;
import java.util.UUID;

@SuppressWarnings("ConstantConditions")
public class BungeeHelper extends ForwardingHelper {

    private static final Gson GSON = new Gson();
    private static final AttributeKey<UUID> UUID_KEY = new AttributeKey<>("-vch-uuid");
    private static final AttributeKey<Property[]> PROPERTIES_KEY = new AttributeKey<>("-vch-properties");
    private final String[] seecret;

    BungeeHelper(String[] seecret) {
        this.seecret = seecret;
    }

    public void parseHandshake(Object connection, Object handshake) {
        try {
            Channel channel = new Invocation(PlayerConnection.class).ofMethod("getChannel").with(connection).invoke();
            String uuid, host = new Invocation(HandshakePacket.class).ofMethod("getHostName").with(handshake).invoke();
            String[] split = host.split("\00", 5);
            if ((split.length != 4 && split.length != 3) || (uuid = split[2]).length() != 32) {
                throw QuietException.show("If you wish to use IP forwarding, please enable it in your BungeeCord config as well!");
            }

        //  split[0]; // we don't do anything with the server address
            new Invocation(PlayerConnection.class).ofMethod("setAddress").with(connection).with(split[1]).invoke();
            channel.attr(UUID_KEY).set(new UUID(
                    Long.parseUnsignedLong(uuid.substring( 0, 16), 16),
                    Long.parseUnsignedLong(uuid.substring(16, 32), 16)
            ));

            if (seecret == null) {
                channel.attr(PROPERTIES_KEY).set((split.length == 3)? new Property[0] : GSON.fromJson(split[3], Property[].class));
                return;
            } else if (split.length == 4) {
                Property[] properties = GSON.fromJson(split[3], Property[].class);
                if (properties.length != 0) {
                    Property[] modified = new Property[properties.length - 1];

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
                        channel.attr(PROPERTIES_KEY).set(modified);
                        return;
                    }
                }
            }
            throw QuietException.show("Received invalid IP forwarding data. Did you use the right forwarding secret?");
        } catch (Exception e) {
            throw exception(null, e);
        }
    }

    public GameProfile injectProfile(Object connection, String username) {
        try {
            Channel channel = new Invocation(PlayerConnection.class).ofMethod("getChannel").with(connection).invoke();
            GameProfile profile = new GameProfile(channel.attr(UUID_KEY).get(), username);
            for (Property property : channel.attr(PROPERTIES_KEY).get()) {
                profile.getProperties().put(property.getName(), property);
            }
            return profile;
        } catch (Exception e) {
            throw exception(null, e);
        }
    }
}
