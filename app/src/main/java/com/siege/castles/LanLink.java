package com.siege.castles;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Direct link between two phones on the same Wi-Fi or on one phone's hotspot, with no internet.
 *
 * The host listens on a TCP port and broadcasts a small "I'm here" beacon. The guest tries
 * its Wi-Fi gateway first (on a hotspot, the gateway is the host phone), then any phone it
 * heard a beacon from, then an address typed by the player. Messages are single lines of
 * JSON; an empty line is a keep-alive so a dropped hotspot is noticed within seconds.
 *
 * Plain Java (Android specifics live behind {@link Platform}) so it can be tested off-device.
 */
public final class LanLink {
    public static final int TCP_PORT = 47123;
    public static final int UDP_PORT = 47124;
    static final String HELLO = "SIEGE-HQ1";

    static int connectTimeoutMs = 1500;
    static int readTimeoutMs = 12000;
    static int keepAliveMs = 3000;

    public interface Listener {
        /** Progress before connecting: "waiting", "searching", "no_wifi" or "port_busy". */
        void onStatus(String code);
        void onOpen();
        void onMessage(String json);
        /** The connection dropped (not called after {@link #stop()}). */
        void onClose();
    }

    public interface Platform {
        /** Addresses that are probably the host, such as the Wi-Fi gateway. */
        List<InetAddress> gateways();
        boolean hasWifi();
        /** A not-yet-connected socket routed through the network that reaches target. */
        Socket newSocket(InetAddress target) throws IOException;
        /** Sends payload as a broadcast on every local network. */
        void broadcast(byte[] payload, int port);
        void keepWifiAwake(boolean on);
    }

    private final Platform platform;
    private final Listener listener;

    // Bumped on every start, stop and drop; background threads exit when it no longer matches.
    private volatile int gen;
    private volatile Socket conn;
    private volatile BlockingQueue<String> outbox;
    private ServerSocket server;
    private DatagramSocket beaconIn;

    public LanLink(Platform platform, Listener listener) {
        this.platform = platform;
        this.listener = listener;
    }

    public synchronized void host() {
        stop();
        final int g = ++gen;
        platform.keepWifiAwake(true);
        final ServerSocket ss;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(TCP_PORT));
        } catch (IOException e) {
            listener.onStatus("port_busy");
            return;
        }
        server = ss;
        listener.onStatus("waiting");
        thread(() -> {
            while (alive(g) && !ss.isClosed()) {
                Socket s = null;
                try {
                    s = ss.accept();
                    if (!attach(s, g, false)) close(s);
                } catch (IOException e) {
                    close(s);
                    if (alive(g) && !ss.isClosed()) sleep(200);
                }
            }
        });
        thread(() -> {
            byte[] beacon = (HELLO + " " + TCP_PORT).getBytes(StandardCharsets.UTF_8);
            while (alive(g) && conn == null) {
                platform.broadcast(beacon, UDP_PORT);
                sleep(1000);
            }
        });
    }

    public synchronized void join(final String manual) {
        stop();
        final int g = ++gen;
        platform.keepWifiAwake(true);
        final Set<InetAddress> heard = Collections.synchronizedSet(new LinkedHashSet<>());
        try {
            final DatagramSocket ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(UDP_PORT));
            beaconIn = ds;
            thread(() -> {
                byte[] buf = new byte[256];
                while (alive(g) && !ds.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (m.startsWith(HELLO)) heard.add(p.getAddress());
                    } catch (IOException e) {
                        if (alive(g) && !ds.isClosed()) sleep(200);
                    }
                }
            });
        } catch (IOException ignored) {
            // The gateway and a typed address still work without beacons.
        }
        thread(() -> {
            while (alive(g) && conn == null) {
                Set<InetAddress> tries = new LinkedHashSet<>();
                if (manual != null && !manual.trim().isEmpty()) {
                    try {
                        tries.add(InetAddress.getByName(manual.trim()));
                    } catch (IOException ignored) {
                    }
                }
                tries.addAll(platform.gateways());
                synchronized (heard) {
                    tries.addAll(heard);
                }
                listener.onStatus(platform.hasWifi() || !tries.isEmpty() ? "searching" : "no_wifi");
                for (InetAddress a : tries) {
                    if (!alive(g) || conn != null) return;
                    Socket s = null;
                    try {
                        s = platform.newSocket(a);
                        s.connect(new InetSocketAddress(a, TCP_PORT), connectTimeoutMs);
                        if (attach(s, g, true)) return;
                        close(s);
                    } catch (IOException e) {
                        close(s);
                    }
                }
                sleep(1000);
            }
        });
    }

    public void send(String json) {
        BlockingQueue<String> q = outbox;
        if (q != null) q.offer(json.replace('\n', ' '));
    }

    public synchronized void stop() {
        ++gen;
        close(server);
        server = null;
        close(beaconIn);
        beaconIn = null;
        close(conn);
        conn = null;
        outbox = null;
        platform.keepWifiAwake(false);
    }

    private boolean attach(Socket s, int g, boolean client) {
        BufferedReader in;
        Writer out;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(4000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            // Both sides prove they are this game before the link opens.
            if (client) {
                out.write(HELLO + "\n");
                out.flush();
                if (!HELLO.equals(in.readLine())) return false;
            } else {
                if (!HELLO.equals(in.readLine())) return false;
                out.write(HELLO + "\n");
                out.flush();
            }
            s.setSoTimeout(readTimeoutMs);
        } catch (IOException e) {
            return false;
        }
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        synchronized (this) {
            if (!alive(g) || conn != null) return false;
            conn = s;
            outbox = q;
            close(server);
            server = null;
            close(beaconIn);
            beaconIn = null;
        }
        // Report the open before any message so the page always sees them in order.
        listener.onOpen();
        thread(() -> readLoop(s, in, g));
        thread(() -> writeLoop(s, out, q, g));
        return true;
    }

    private void readLoop(Socket s, BufferedReader in, int g) {
        try {
            String line;
            while ((line = in.readLine()) != null && alive(g)) {
                if (!line.isEmpty()) listener.onMessage(line);
            }
        } catch (IOException ignored) {
        }
        dropped(s, g);
    }

    private void writeLoop(Socket s, Writer out, BlockingQueue<String> q, int g) {
        try {
            while (alive(g) && conn == s) {
                String m = q.poll(keepAliveMs, TimeUnit.MILLISECONDS);
                out.write(m == null ? "\n" : m + "\n");
                if (q.isEmpty()) out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
        }
        dropped(s, g);
    }

    private void dropped(Socket s, int g) {
        synchronized (this) {
            if (conn != s || !alive(g)) return;
            ++gen;
            conn = null;
            outbox = null;
            platform.keepWifiAwake(false);
        }
        close(s);
        listener.onClose();
    }

    private boolean alive(int g) {
        return gen == g;
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void thread(Runnable r) {
        Thread t = new Thread(r, "lan-link");
        t.setDaemon(true);
        t.start();
    }
}
