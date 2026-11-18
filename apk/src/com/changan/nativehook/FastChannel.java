package com.changan.nativehook;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;

/** 4455 快速命令通道 / 4466 HTTP 文件通道。 */
final class FastChannel {

    static final int SHELL_PORT = 4455;
    static final int HTTP_PORT = 4466;
    private static final String HOST = "127.0.0.1";

    private FastChannel() {
    }

    static boolean isPortOpen(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 通过 rt_shell 执行一条命令，读到 __NH_DONE__ 或超时。 */
    static String exec(String command, int timeoutMs) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, SHELL_PORT), 2000);
            // 交互式 sh 的提示符会持续输出，这里限制单次读取的静默时间
            s.setSoTimeout(Math.max(2000, Math.min(timeoutMs, 15000)));
            OutputStream out = s.getOutputStream();
            String body = command.endsWith("\n") ? command : command + "\n";
            // 不能拼 "; echo ..."：命令本身以换行结尾时会变成 "\n;" 语法错误
            out.write((body + "echo __NH_DONE__\nexit\n").getBytes("UTF-8"));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int n;
                try {
                    n = in.read(b);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                if (n < 0) break;
                buf.write(b, 0, n);
                if (buf.toString("UTF-8").contains("__NH_DONE__")) break;
            }
            return buf.toString("UTF-8");
        }
    }

    /** 车机 IPv4：优先 wlan/ap/eth，跳过 ccmni/rmnet/tun/p2p。 */
    static String ipAddress() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (name.startsWith("ccmni") || name.startsWith("rmnet")
                        || name.startsWith("tun") || name.startsWith("p2p")) {
                    continue;
                }
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("ap")
                                || name.startsWith("eth")) {
                            return ip;
                        }
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback == null ? "" : fallback;
        } catch (Exception e) {
            return "";
        }
    }
}