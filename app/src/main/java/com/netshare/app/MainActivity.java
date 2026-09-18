package com.netshare.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends Activity {

    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;

    private TextView tvTitle, tvSsid, tvPass, tvProxy, tvConnected;
    private Button btnToggle;
    public static final int PORT = 1080;

    // Счетчики трафика как в PdaNet
    private final AtomicLong bytesIn = new AtomicLong(0);
    private final AtomicLong bytesOut = new AtomicLong(0);
    private String connectedClientIp = "Ожидание подключения...";
    private Handler uiHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        uiHandler = new Handler(Looper.getMainLooper());

        p2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (p2pManager != null) {
            p2pChannel = p2pManager.initialize(this, getMainLooper(), null);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.NEARBY_WIFI_DEVICES"
            }, 1);
        }

        // Интерфейс точно как синий блок в PdaNet
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 40, 30, 30);
        root.setBackgroundColor(Color.parseColor("#121212"));

        TextView appHeader = new TextView(this);
        appHeader.setText("NetShare Direct");
        appHeader.setTextSize(24);
        appHeader.setTextColor(Color.WHITE);
        appHeader.setPadding(0, 0, 0, 30);
        root.addView(appHeader);

        // Синяя информационная панель
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setBackgroundColor(Color.parseColor("#0072C6")); // PdaNet Blue
        infoBox.setPadding(30, 30, 30, 30);

        tvTitle = new TextView(this);
        tvTitle.setText("Подключите ПК по Wi-Fi к:");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(16);
        infoBox.addView(tvTitle);

        tvSsid = new TextView(this);
        tvSsid.setText("Имя: нажмите Запуск");
        tvSsid.setTextColor(Color.WHITE);
        tvSsid.setTextSize(18);
        tvSsid.setPadding(0, 10, 0, 5);
        infoBox.addView(tvSsid);

        tvPass = new TextView(this);
        tvPass.setText("Пароль: —");
        tvPass.setTextColor(Color.WHITE);
        tvPass.setTextSize(18);
        tvPass.setPadding(0, 0, 0, 5);
        infoBox.addView(tvPass);

        tvProxy = new TextView(this);
        tvProxy.setText("Proxy: 192.168.49.1 : " + PORT);
        tvProxy.setTextColor(Color.parseColor("#D0E8FF"));
        tvProxy.setTextSize(15);
        tvProxy.setPadding(0, 0, 0, 15);
        infoBox.addView(tvProxy);

        // Строка со счетчиком трафика
        tvConnected = new TextView(this);
        tvConnected.setText("Connected: Нет устройств - 0.00M/0.00M");
        tvConnected.setTextColor(Color.YELLOW);
        tvConnected.setTextSize(15);
        infoBox.addView(tvConnected);

        root.addView(infoBox);

        btnToggle = new Button(this);
        btnToggle.setText("ВКЛЮЧИТЬ WIFI DIRECT");
        btnToggle.setTextSize(18);
        btnToggle.setBackgroundColor(Color.parseColor("#008000"));
        btnToggle.setTextColor(Color.WHITE);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150);
        btnParams.setMargins(0, 50, 0, 0);
        btnToggle.setLayoutParams(btnParams);
        root.addView(btnToggle);

        setContentView(root);

        btnToggle.setOnClickListener(v -> {
            if (!isRunning) startAll();
            else stopAll();
        });

        // Запуск таймера обновления счетчика трафика на экране (раз в секунду)
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    double mbIn = bytesIn.get() / (1024.0 * 1024.0);
                    double mbOut = bytesOut.get() / (1024.0 * 1024.0);
                    String stat = String.format("Connected: %s - %.2fM/%.2fM", connectedClientIp, mbIn, mbOut);
                    tvConnected.setText(stat);
                }
                uiHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void startAll() {
        isRunning = true;
        bytesIn.set(0);
        bytesOut.set(0);
        connectedClientIp = "Ожидание ПК...";
        btnToggle.setText("ОСТАНОВИТЬ");
        btnToggle.setBackgroundColor(Color.RED);
        tvSsid.setText("Запуск заводской сети...");

        // 1. Создаем сеть заводским методом (как PdaNet)
        startNativeWifiDirect();

        // 2. Запускаем высокоскоростной сервер
        startProxyServer();
    }

    private void startNativeWifiDirect() {
        if (p2pManager == null || p2pChannel == null) return;

        // Создаем группу БЕЗ принудительного конфига (чтобы чип сам включил маяк)
        p2pManager.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // Считываем заводское имя и пароль (как PdaNet)
                p2pManager.requestGroupInfo(p2pChannel, new WifiP2pManager.GroupInfoListener() {
                    @Override
                    public void onGroupInfoAvailable(WifiP2pGroup group) {
                        if (group != null) {
                            tvSsid.setText("Имя: " + group.getNetworkName());
                            tvPass.setText("Пароль: " + group.getPassphrase());
                        }
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                tvSsid.setText("Сбой создания группы: " + reason);
            }
        });
    }

    private void stopAll() {
        isRunning = false;
        btnToggle.setText("ВКЛЮЧИТЬ WIFI DIRECT");
        btnToggle.setBackgroundColor(Color.parseColor("#008000"));
        tvSsid.setText("Имя: нажмите Запуск");
        tvPass.setText("Пароль: —");
        tvConnected.setText("Connected: Остановлен");

        if (p2pManager != null && p2pChannel != null) {
            p2pManager.removeGroup(p2pChannel, null);
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (Exception ignored) {}
    }

    private void startProxyServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (isRunning) {
                    Socket client = serverSocket.accept();
                    connectedClientIp = client.getInetAddress().getHostAddress();
                    new Thread(new SocksHandler(client)).start();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private class SocksHandler implements Runnable {
        private final Socket client;

        public SocksHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                int ver = in.read();
                if (ver != 5) { client.close(); return; }
                int nmethods = in.read();
                byte[] methods = new byte[nmethods];
                in.read(methods);
                out.write(new byte[]{0x05, 0x00});
                out.flush();

                in.read(); // ver
                int cmd = in.read(); // 0x01: TCP, 0x03: UDP
                in.read(); // rsv
                int atyp = in.read();

                if (cmd == 0x01) {
                    String host = readHost(in, atyp);
                    int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

                    Socket remote = new Socket(host, port);
                    out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                    out.flush();
                    pipe(client, remote);
                } else if (cmd == 0x03) {
                    DatagramSocket udpSocket = new DatagramSocket();
                    int localUdpPort = udpSocket.getLocalPort();
                    byte[] bndPort = new byte[]{(byte) (localUdpPort >> 8), (byte) (localUdpPort & 0xFF)};
                    out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, bndPort[0], bndPort[1]});
                    out.flush();
                    handleUdpRelay(client, udpSocket);
                } else {
                    client.close();
                }
            } catch (Exception ignored) {
                try { client.close(); } catch (Exception ignored2) {}
            }
        }

        private String readHost(InputStream in, int atyp) throws Exception {
            if (atyp == 0x01) {
                byte[] ip = new byte[4];
                in.read(ip);
                return InetAddress.getByAddress(ip).getHostAddress();
            } else if (atyp == 0x03) {
                int len = in.read();
                byte[] host = new byte[len];
                in.read(host);
                return new String(host);
            }
            throw new Exception("Unknown ATYP");
        }

        private void handleUdpRelay(Socket controlSocket, DatagramSocket udpSocket) {
            new Thread(() -> {
                try {
                    byte[] buf = new byte[65535];
                    InetAddress clientIp = controlSocket.getInetAddress();
                    int clientUdpPort = -1;

                    while (!controlSocket.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        udpSocket.receive(packet);

                        if (packet.getAddress().equals(clientIp)) {
                            clientUdpPort = packet.getPort();
                            if (buf[2] != 0) continue;
                            int atyp = buf[3];
                            int offset = 4;
                            InetAddress targetAddr;
                            if (atyp == 0x01) {
                                targetAddr = InetAddress.getByAddress(Arrays.copyOfRange(buf, offset, offset + 4));
                                offset += 4;
                            } else continue;

                            int targetPort = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
                            offset += 2;

                            int payloadLen = packet.getLength() - offset;
                            bytesOut.addAndGet(payloadLen); // Считаем исходящий трафик
                            DatagramPacket outPkt = new DatagramPacket(buf, offset, payloadLen, targetAddr, targetPort);
                            udpSocket.send(outPkt);
                        } else if (clientUdpPort != -1) {
                            byte[] resp = new byte[packet.getLength() + 10];
                            resp[0] = 0; resp[1] = 0; resp[2] = 0; resp[3] = 1;
                            byte[] rawIp = packet.getAddress().getAddress();
                            System.arraycopy(rawIp, 0, resp, 4, 4);
                            resp[8] = (byte) (packet.getPort() >> 8);
                            resp[9] = (byte) (packet.getPort() & 0xFF);
                            System.arraycopy(packet.getData(), 0, resp, 10, packet.getLength());

                            bytesIn.addAndGet(packet.getLength()); // Считаем входящий трафик
                            DatagramPacket backPkt = new DatagramPacket(resp, resp.length, clientIp, clientUdpPort);
                            udpSocket.send(backPkt);
                        }
                    }
                } catch (Exception ignored) {}
                finally { udpSocket.close(); }
            }).start();
        }

        private void pipe(Socket a, Socket b) {
            new Thread(() -> forward(a, b, bytesOut)).start();
            forward(b, a, bytesIn);
        }

        private void forward(Socket src, Socket dst, AtomicLong counter) {
            try {
                byte[] buf = new byte[32768];
                InputStream in = src.getInputStream();
                OutputStream out = dst.getOutputStream();
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    out.flush();
                    counter.addAndGet(len); // Считаем каждый байт
                }
            } catch (Exception ignored) {}
            try { src.close(); } catch (Exception ignored) {}
            try { dst.close(); } catch (Exception ignored) {}
        }
    }
}
