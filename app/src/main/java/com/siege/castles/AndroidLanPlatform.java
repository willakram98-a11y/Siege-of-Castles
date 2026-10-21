package com.siege.castles;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Android side of {@link LanLink}. When a Wi-Fi network has no internet, Android keeps
 * mobile data as the default route, so sockets to the Wi-Fi are bound to that network.
 * The phone's own hotspot is reached directly, because Android routes tethered networks
 * for every app.
 */
final class AndroidLanPlatform implements LanLink.Platform {
    private final ConnectivityManager cm;
    private final WifiManager.MulticastLock lock;

    AndroidLanPlatform(Context context) {
        Context app = context.getApplicationContext();
        cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        lock = wm != null ? wm.createMulticastLock("siege-lan") : null;
        if (lock != null) lock.setReferenceCounted(false);
    }

    @SuppressWarnings("deprecation")
    private List<Network> wifiNetworks() {
        List<Network> out = new ArrayList<>();
        if (cm == null) return out;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.add(n);
        }
        return out;
    }

    @Override
    public boolean hasWifi() {
        return !wifiNetworks().isEmpty();
    }

    @Override
    public List<InetAddress> gateways() {
        List<InetAddress> out = new ArrayList<>();
        for (Network n : wifiNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (RouteInfo r : lp.getRoutes()) {
                InetAddress gw = r.getGateway();
                if (r.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress() && !out.contains(gw)) {
                    out.add(gw);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Inet4Address dhcp = lp.getDhcpServerAddress();
                if (dhcp != null && !dhcp.isAnyLocalAddress() && !out.contains(dhcp)) out.add(dhcp);
            }
        }
        return out;
    }

    @Override
    public Socket newSocket(InetAddress target) throws IOException {
        Socket s = new Socket();
        Network n = wifiNetworkFor(target);
        if (n != null) n.bindSocket(s);
        return s;
    }

    /** The Wi-Fi network whose subnet holds target, or null (e.g. a phone on our own hotspot). */
    private Network wifiNetworkFor(InetAddress target) {
        for (Network n : wifiNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (sameSubnet(la.getAddress(), la.getPrefixLength(), target)) return n;
            }
        }
        return null;
    }

    @Override
    public void broadcast(byte[] payload, int port) {
        // Wi-Fi networks this phone has joined.
        for (Network n : wifiNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                n.bindSocket(ds);
                send(ds, payload, InetAddress.getByName("255.255.255.255"), port);
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress b = broadcastOf(la.getAddress(), la.getPrefixLength());
                    if (b != null) send(ds, payload, b, port);
                }
            } catch (IOException ignored) {
            }
        }
        // Every local interface, including this phone's own hotspot.
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            for (InterfaceAddress ia : interfaceAddresses()) {
                if (ia.getBroadcast() != null) send(ds, payload, ia.getBroadcast(), port);
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void keepWifiAwake(boolean on) {
        if (lock == null) return;
        try {
            if (on) lock.acquire();
            else if (lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** This phone's local IPv4 addresses (hotspot and Wi-Fi), for the player to read out. */
    static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        for (InterfaceAddress ia : interfaceAddresses()) {
            InetAddress a = ia.getAddress();
            if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
        }
        return out;
    }

    private static List<InterfaceAddress> interfaceAddresses() {
        List<InterfaceAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return out;
            for (NetworkInterface ni : Collections.list(all)) {
                String name = ni.getName() == null ? "" : ni.getName();
                // Skip mobile-data and virtual interfaces; they never reach the other phone.
                if (!ni.isUp() || ni.isLoopback() || name.startsWith("rmnet") || name.startsWith("ccmni")
                        || name.startsWith("clat") || name.startsWith("v4-") || name.startsWith("dummy")
                        || name.startsWith("tun")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) out.add(ia);
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void send(DatagramSocket ds, byte[] p, InetAddress to, int port) {
        try {
            ds.send(new DatagramPacket(p, p.length, to, port));
        } catch (IOException ignored) {
        }
    }

    private static boolean sameSubnet(InetAddress a, int prefix, InetAddress b) {
        if (!(a instanceof Inet4Address) || !(b instanceof Inet4Address) || prefix <= 0) return false;
        int mask = prefix >= 32 ? -1 : -1 << (32 - prefix);
        return (toInt(a) & mask) == (toInt(b) & mask);
    }

    private static InetAddress broadcastOf(InetAddress a, int prefix) {
        if (!(a instanceof Inet4Address) || prefix <= 0 || prefix >= 31) return null;
        int b = toInt(a) | ~(-1 << (32 - prefix));
        try {
            return InetAddress.getByAddress(new byte[]{(byte) (b >>> 24), (byte) (b >>> 16), (byte) (b >>> 8), (byte) b});
        } catch (IOException e) {
            return null;
        }
    }

    private static int toInt(InetAddress a) {
        byte[] x = a.getAddress();
        return ((x[0] & 0xff) << 24) | ((x[1] & 0xff) << 16) | ((x[2] & 0xff) << 8) | (x[3] & 0xff);
    }
}
