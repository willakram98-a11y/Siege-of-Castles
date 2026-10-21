package com.siege.castles;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Direct link between two phones on the same Wi-Fi or on one phone's hotspot, with no internet.
 *
 * The host listens on a TCP port and broadcasts a small "I'm here" beacon. The guest tries
 * its Wi-Fi gateway first (on a hotspot, the gateway is the host phone), then phones it
 * recently heard a beacon from, then an address typed by the player. Messages are single
 * lines of JSON; an empty line is a keep-alive so a dropped hotspot is noticed in seconds.
 *
 * Every host()/join() starts a new session number, and every event carries it, so the page
 * can ignore anything left over from an earlier attempt.
 *
 * Plain Java (Android specifics live behind {@link Platform}) so it can be tested off-device.
 */
public final class LanLink {
    public static final int TCP_PORT = 47123;
    public static final int UDP_PORT = 47124;
    static final String HELLO = "SIEGE-HQ2";
    static final String GO = "GO";

    static int connectTimeoutMs = 1500;
    static int handshakeTimeoutMs = 3000;
    static int readTimeoutMs = 12000;
    static int keepAliveMs = 3000;
    static int forgetBeaconMs = 5000;
    static int maxLineChars = 4_000_000;

    public interface Listener {
        /** Progress before connecting: "waiting", "searching", "no_wifi" or "port_busy". */
        void onStatus(int session, String code);
        void onOpen(int session);
        void onMessage(int session, String json);
        /** The connection dropped (not called after {@link #stop()}). */
        void onClose(int session);
    }

    public interface Platform {
        /** Addresses that are probably the host, such as the Wi-Fi gateway. */
        java.util.List<InetAddress> gateways();
        boolean hasWifi();
        /** A not-yet-connected socket routed through the network that reaches target. */
        Socket newSocket(InetAddress target) throws IOException;
        /** Sends payload as a broadcast on every local network. */
        void broadcast(byte[] payload, int port);
        void keepWifiAwake(boolean on);
    }

    private final Platform platform;
    private final Listener listener;

    // Session number: bumped on every start, stop and drop. Threads of an old session exit.
    private volatile int gen;
    private volatile Socket conn;
    private BlockingQueue<String> outbox;
    private ServerSocket server;
    private DatagramSocket beaconIn;
    // Sockets still connecting or handshaking; stop() closes them so none can finish late.
    private final Set<Socket> pending = new HashSet<>();
    private String lastStatus;

    public LanLink(Platform platform, Listener listener) {
        this.platform = platform;
        this.listener = listener;
    }

    /** Starts hosting and returns the new session number. */
    public synchronized int host() {
        stop();
        final int g = ++gen;
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(TCP_PORT));
        } catch (IOException e) {
            close(ss);
            status(g, "port_busy");
            return g;
        }
        server = ss;
        platform.keepWifiAwake(true);
        status(g, "waiting");
        final ServerSocket listening = ss;
        thread(() -> {
            while (alive(g) && !listening.isClosed()) {
                try {
                    final Socket s = listening.accept();
                    // Handshake on its own thread so a silent client cannot hold up the real guest.
                    if (track(s, g)) thread(() -> handshake(s, g, false));
                    else close(s);
                } catch (IOException e) {
                    if (alive(g) && !listening.isClosed()) sleep(200);
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
        return g;
    }

    /** Starts looking for a host (plus an optional typed IPv4 address) and returns the session number. */
    public synchronized int join(String typedAddress) {
        stop();
        final int g = ++gen;
        platform.keepWifiAwake(true);
        final InetAddress typed = parseIPv4(typedAddress);
        final Map<InetAddress, Long> heard = new ConcurrentHashMap<>();
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(UDP_PORT));
            beaconIn = ds;
            final DatagramSocket in = ds;
            thread(() -> {
                byte[] buf = new byte[256];
                while (alive(g) && !in.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        in.receive(p);
                        String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (m.startsWith(HELLO)) heard.put(p.getAddress(), System.currentTimeMillis());
                    } catch (IOException e) {
                        if (alive(g) && !in.isClosed()) sleep(200);
                    }
                }
            });
        } catch (IOException ignored) {
            close(ds); // The gateway and a typed address still work without beacons.
        }
        thread(() -> {
            while (alive(g) && conn == null) {
                Set<InetAddress> tries = new LinkedHashSet<>();
                if (typed != null) tries.add(typed);
                tries.addAll(platform.gateways());
                long now = System.currentTimeMillis();
                heard.values().removeIf(t -> now - t > forgetBeaconMs);
                tries.addAll(heard.keySet());
                status(g, platform.hasWifi() || !tries.isEmpty() ? "searching" : "no_wifi");
                for (InetAddress a : tries) {
                    if (!alive(g) || conn != null) return;
                    Socket s = null;
                    try {
                        s = platform.newSocket(a);
                        if (!track(s, g)) {
                            close(s);
                            return;
                        }
                        s.connect(new InetSocketAddress(a, TCP_PORT), connectTimeoutMs);
                        if (handshake(s, g, true)) return;
                    } catch (IOException e) {
                        untrack(s);
                    }
                }
                sleep(1000);
            }
        });
        return g;
    }

    public void send(String json) {
        BlockingQueue<String> q;
        synchronized (this) {
            q = outbox;
        }
        if (q != null) q.offer(json.replace('\n', ' '));
    }

    public synchronized void stop() {
        ++gen;
        teardown();
    }

    /** Closes everything the current session holds. Caller holds the lock. */
    private void teardown() {
        close(server);
        server = null;
        close(beaconIn);
        beaconIn = null;
        close(conn);
        conn = null;
        for (Socket s : new ArrayList<>(pending)) close(s);
        pending.clear();
        outbox = null;
        lastStatus = null;
        platform.keepWifiAwake(false);
    }

    private synchronized boolean track(Socket s, int g) {
        if (!alive(g)) return false;
        pending.add(s);
        return true;
    }

    private void untrack(Socket s) {
        if (s == null) return;
        synchronized (this) {
            pending.remove(s);
        }
        close(s);
    }

    /**
     * Guest: HELLO, host: HELLO, guest: GO. The guest sends GO only while its session is still
     * current, so a cancelled attempt can never show up as connected on the host.
     */
    private boolean handshake(Socket s, int g, boolean client) {
        BufferedReader in;
        Writer out;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(handshakeTimeoutMs);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            if (client) {
                out.write(HELLO + "\n");
                out.flush();
                if (!HELLO.equals(readLine(in, 64))) throw new IOException("not a game");
            } else {
                if (!HELLO.equals(readLine(in, 64))) throw new IOException("not a game");
                out.write(HELLO + "\n");
                out.flush();
                if (!GO.equals(readLine(in, 64))) throw new IOException("guest gave up");
            }
            s.setSoTimeout(readTimeoutMs);
        } catch (IOException e) {
            untrack(s);
            return false;
        }
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        synchronized (this) {
            if (!alive(g) || conn != null || !pending.remove(s)) {
                close(s);
                return false;
            }
            if (client) {
                try {
                    out.write(GO + "\n");
                    out.flush();
                } catch (IOException e) {
                    close(s);
                    return false;
                }
            }
            conn = s;
            outbox = q;
            close(server);
            server = null;
            close(beaconIn);
            beaconIn = null;
            for (Socket o : new ArrayList<>(pending)) close(o);
            pending.clear();
            // Reported under the lock and before any message, so the page sees them in order.
            listener.onOpen(g);
        }
        thread(() -> readLoop(s, in, g));
        thread(() -> writeLoop(s, out, q, g));
        return true;
    }

    private void readLoop(Socket s, Reader in, int g) {
        try {
            String line;
            while ((line = readLine(in, maxLineChars)) != null && alive(g)) {
                if (!line.isEmpty()) listener.onMessage(g, line);
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

    private synchronized void dropped(Socket s, int g) {
        if (conn != s || !alive(g)) return;
        ++gen;
        teardown();
        listener.onClose(g);
    }

    private synchronized void status(int g, String code) {
        if (!alive(g) || code.equals(lastStatus)) return;
        lastStatus = code;
        listener.onStatus(g, code);
    }

    private boolean alive(int g) {
        return gen == g;
    }

    /** Reads one line; fails instead of growing past max characters. Returns null at end of stream. */
    static String readLine(Reader in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (sb.length() >= max) throw new IOException("line too long");
            sb.append((char) c);
        }
        return null;
    }

    /** A dotted IPv4 address with every part 0-255, or null. Never does a DNS lookup. */
    static InetAddress parseIPv4(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            for (int k = 0; k < p.length(); k++) if (p.charAt(k) < '0' || p.charAt(k) > '9') return null;
            int v = Integer.parseInt(p);
            if (v > 255) return null;
            b[i] = (byte) v;
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (IOException e) {
            return null;
        }
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
