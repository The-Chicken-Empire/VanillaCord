package uk.co.thinkofdeath.vanillacord.helper;

import com.mojang.authlib.properties.Property;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.EmptyByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static uk.co.thinkofdeath.vanillacord.helper.BungeeHelper.UUID_KEY;
import static uk.co.thinkofdeath.vanillacord.helper.BungeeHelper.PROPERTIES_KEY;

@SuppressWarnings("JavaReflectionInvocation")
public class VelocityHelper {

    private static final Object NAMESPACE;
    public static final AttributeKey<Integer> TRANSACTION_ID_KEY = AttributeKey.valueOf("-vch-transaction");
    public static final AttributeKey<Object> INTERCEPTED_PACKET_KEY = AttributeKey.valueOf("-vch-intercepted");

    private static byte[] seecret = null;
    private static int lastTID = Integer.MIN_VALUE;

    public static void initializeTransaction(Object networkManager, Object intercepted) {
        try {
            Channel channel = (Channel) NetworkManager.channel.get(networkManager);
            if (channel.attr(TRANSACTION_ID_KEY).get() != null) {
                throw new IllegalStateException("Unexpected login request");
            }

            // Reserve a unique key
            int key;
            synchronized (NAMESPACE) {
                if (lastTID == Integer.MAX_VALUE) lastTID = Integer.MIN_VALUE;
                key = ++lastTID;
            }
            channel.attr(TRANSACTION_ID_KEY).set(key);
            channel.attr(INTERCEPTED_PACKET_KEY).set(intercepted);

            // Construct the packet

            Object qObject;
            if (LoginRequestPacket.useFields) {
                qObject = LoginRequestPacket.clazz.newInstance();

                LoginRequestPacket.transactionID.set(qObject, key);
                LoginRequestPacket.namespace.set(qObject, NAMESPACE);
                LoginRequestPacket.data.set(qObject, PacketData.constructor.newInstance(new EmptyByteBuf(ByteBufAllocator.DEFAULT)));
            } else {
                qObject = LoginRequestPacket.constructor.newInstance(key, NAMESPACE, PacketData.constructor.newInstance(new EmptyByteBuf(ByteBufAllocator.DEFAULT)));
            }

            // Send the packet
            NetworkManager.sendPacket.invoke(networkManager, qObject);

        } catch (Exception e) {
            e.printStackTrace();
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    public static void completeTransaction(Object networkManager, Object loginManager, Object response, String secret) {
        try {
            Channel channel = (Channel) NetworkManager.channel.get(networkManager);
            Object intercepted = channel.attr(INTERCEPTED_PACKET_KEY).getAndSet(null);
            if (intercepted == null) {
                throw new IllegalStateException("Unexpected login response");
            }

            // Retrieve & release the previously generated unique key
            int key = (int) LoginResponsePacket.transactionID.get(response);
            ByteBuf data = (ByteBuf) LoginResponsePacket.data.get(response);

            if (key != channel.attr(TRANSACTION_ID_KEY).get()) {
                throw new QuietException("Invalid transaction ID: " + key);
            } if (data == null) {
                throw new QuietException("If you wish to use modern IP forwarding, please enable it in your Velocity config as well!");
            }


            // Validate the signature on the data
            {
                byte[] received = new byte[32];
                byte[] raw = new byte[data.readableBytes() - received.length];

                data.readBytes(received);
                data.copy(received.length, raw.length).readBytes(raw);
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(getSecret(secret), mac.getAlgorithm()));
                mac.update(raw);
                byte[] calculated = mac.doFinal();
                if (!Arrays.equals(calculated, received)) {
                    StringBuilder s1 = new StringBuilder();
                    for (int i=0; i < calculated.length; i++) {
                        s1.append(Integer.toString((calculated[i] & 0xff) + 0x100, 16).substring(1));
                    }
                    StringBuilder s2 = new StringBuilder();
                    for (int i=0; i < received.length; i++) {
                        s2.append(Integer.toString((received[i] & 0xff) + 0x100, 16).substring(1));
                    }

                    throw new QuietException("Received invalid IP forwarding data: " + s1 + " != " + s2);
                }
            }

            // Compare versioning information
            int version = readVarInt(data); /*
            if (version != 1) {
                throw new IPForwardingException("Received incompatible IP forwarding data")
            } */

            // Retrieve IP forwarding data
            NetworkManager.socket.set(networkManager, new InetSocketAddress(readString(data), ((InetSocketAddress) NetworkManager.socket.get(networkManager)).getPort()));
            channel.attr(UUID_KEY).set(new UUID(data.readLong(), data.readLong()));

            readString(data); // we don't do anything with the username field

            Property[] properties = new Property[readVarInt(data)];
            for (int i = 0; i < properties.length; ++i) {
                properties[i] = new Property(readString(data), readString(data), (data.readBoolean())? readString(data) : null);
            }
            channel.attr(PROPERTIES_KEY).set(properties);

            // Continue login flow
            LoginListener.handleIntercepted.invoke(loginManager, intercepted);
        } catch (Exception e) {
            e.printStackTrace();
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    static {
        try {
            NAMESPACE = NamespacedKey.constructor.newInstance("velocity", "player_info");
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    private static byte[] getSecret(String def) throws IOException {
        if (seecret == null) {
            File config = new File("seecret.txt");
            if (config.exists()) {
                Properties properties = new Properties();
                try (FileInputStream reader = new FileInputStream(config)) {
                    properties.load(reader);
                    seecret = properties.getProperty("modern-forwarding-secret", def).getBytes(UTF_8);
                }
            } else {
                seecret = def.getBytes(UTF_8);
                PrintWriter writer = new PrintWriter(config, UTF_8.name());
                writer.println("# Hey, there. We know you already patched in a default secret key for VanillaCord to use,");
                writer.println("# but if you ever need to change it, you can do so here without re-installing the patches.");
                writer.println("# ");
                writer.println("# This file is automatically generated by VanillaCord once a player attempts to join the server.");
                writer.println();
                writer.println("modern-forwarding-secret=" + def);
                writer.close();
            }
        }
        return seecret;
    }

    private static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len > Short.MAX_VALUE * 4) {
            throw new RuntimeException("String is too long");
        }

        byte[] b = new byte[len];
        buf.readBytes(b);

        String s = new String(b, UTF_8);
        if (s.length() > Short.MAX_VALUE) {
            throw new RuntimeException("String is too long");
        }

        return s;
    }

    private static int readVarInt(ByteBuf input) {
        int out = 0;
        int bytes = 0;
        byte in;
        do {
            in = input.readByte();
            out |= (in & 0x7F) << (bytes++ * 7);

            if (bytes > 5) {
                throw new RuntimeException("VarInt is too big");
            }
        } while ((in & 0x80) == 0x80);

        return out;
    }

    // Pre-calculate reflection for obfuscated references
    static final class NetworkManager {
        public static final Class<?> clazz;
        public static final Field channel;
        public static final Field socket;
        public static final Method sendPacket;

        static {
            try {
                clazz = BungeeHelper.NetworkManager.clazz;

                channel = BungeeHelper.NetworkManager.channel;
                socket = BungeeHelper.NetworkManager.socket;
                sendPacket = clazz.getDeclaredMethod("VCMR-NetworkManager-SendPacket", Class.forName("VCTR-Packet"));
            } catch (Throwable e) {
                (e = new RuntimeException("Class generation failed", e)).printStackTrace();
                throw (RuntimeException) e;
            }
        }
    }

    static final class LoginListener {
        public static final Class<?> clazz;
        public static final Method handleIntercepted;

        static {
            try {
                clazz = Class.forName("VCTR-LoginListener");
                handleIntercepted = clazz.getMethod("VCMR-LoginListener-HandleIntercepted", Class.forName("VCTR-InterceptedPacket"));
            } catch (Throwable e) {
                (e = new RuntimeException("Class generation failed", e)).printStackTrace();
                throw (RuntimeException) e;
            }
        }
    }

    static final class NamespacedKey {
        public static final Class<?> clazz;
        public static final Constructor<?> constructor;

        static {
            try {
                clazz = Class.forName("VCTR-NamespacedKey");
                constructor = clazz.getConstructor(String.class, String.class);
            } catch (Throwable e) {
                (e = new RuntimeException("Class generation failed", e)).printStackTrace();
                throw (RuntimeException) e;
            }
        }
    }

    static final class PacketData {
        public static final Class<?> clazz;
        public static final Constructor<?> constructor;

        static {
            try {
                clazz = Class.forName("VCTR-PacketData");
                constructor = clazz.getConstructor(ByteBuf.class);
            } catch (Throwable e) {
                (e = new RuntimeException("Class generation failed", e)).printStackTrace();
                throw (RuntimeException) e;
            }
        }
    }

    static final class LoginRequestPacket {
        public static final Class<?> clazz;
        public static final Constructor<?> constructor;
        public static final boolean useFields;
        public static final Field transactionID;
        public static final Field namespace;
        public static final Field data;

        static {
            try {
                clazz = Class.forName("VCTR-LoginRequestPacket");
                Constructor<?> construct = null;
                Field[] fields = new Field[3];
                try {
                    construct = clazz.getConstructor(int.class, NamespacedKey.clazz, PacketData.clazz);
                } catch (Throwable e) {
                    fields[0] = clazz.getDeclaredField("VCFR-LoginRequestPacket-TransactionID");
                    fields[0].setAccessible(true);

                    fields[1] = clazz.getDeclaredField("VCFR-LoginRequestPacket-Namespace");
                    fields[1].setAccessible(true);

                    fields[2] = clazz.getDeclaredField("VCFR-LoginRequestPacket-Data");
                    fields[2].setAccessible(true);
                }

                constructor = construct;
                useFields = constructor == null;
                transactionID = fields[0];
                namespace = fields[1];
                data = fields[2];

            } catch (Throwable e) {
                (e = new RuntimeException("Class generation failed", e)).printStackTrace();
                throw (RuntimeException) e;
            }
        }
    }

    static final class LoginResponsePacket {
        public static final Class<?> clazz;
        public static final Field transactionID;
        public static final Field data;

        static {
            try {
                clazz = Class.forName("VCTR-LoginResponsePacket");

                transactionID = clazz.getDeclaredField("VCFR-LoginResponsePacket-TransactionID");
                transactionID.setAccessible(true);

                data = clazz.getDeclaredField("VCFR-LoginResponsePacket-Data");
                data.setAccessible(true);
            } catch (Throwable e) {
                (e = new RuntimeException("Class generation failed", e)).printStackTrace();
                throw (RuntimeException) e;
            }
        }
    }
}
