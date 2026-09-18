package com.netshare.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {

    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private TextView tvStatus, tvIp;
    private Button btnToggle;
    public static final int PORT = 8000; // Стандартный порт PdaNet

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 60, 50, 40);
        layout.setBackgroundColor(Color.parseColor("#121212"));

        TextView title = new TextView(this);
        title.setText("NetShare Server");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        layout.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setText("Статус: Остановлен");
        tvStatus.setTextSize(18);
        tvStatus.setTextColor(Color.LTGRAY);
        tvStatus.setPadding(0, 30, 0, 10);
        layout.addView(tvStatus);

        tvIp = new TextView(this);
        tvIp.setText("IP: —");
        tvIp.setTextSize(16);
        tvIp.setTextColor(Color.parseColor("#00B0FF"));
        tvIp.setPadding(0, 0, 0, 40);
        layout.addView(tvIp);

        btnToggle = new Button(this);
        btnToggle.setText("ЗАПУСТИТЬ РАЗДАЧУ");
        btnToggle.setTextSize(18);
        btnToggle.setBackgroundColor(Color.parseColor("#007ACC"));
        btnToggle.setTextColor(Color.WHITE);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150);
        btnToggle.setLayoutParams(params);
        layout.addView(btnToggle);

        setContentView(layout);

        btnToggle.setOnClickListener(v -> {
            if (!isRunning) startServer();
            else stopServer();
        });
    }

    private void startServer() {
        isRunning = true;
        btnToggle.setText("ОСТАНОВИТЬ");
        btnToggle.setBackgroundColor(Color.RED);
        tvStatus.setText("Статус: РАБОТАЕТ (Порт " + PORT + ")");
        tvStatus.setTextColor(Color.GREEN);

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
            if (ip.equals("0.0.0.0")) ip = "192.168.49.1 / 192.168.43.1";
            tvIp.setText("IP для подключения: " + ip);
        } catch (Exception e) {
            tvIp.setText("IP: режим USB / Точка доступа");
        }

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (isRunning) {
                    Socket client = serverSocket.accept();
                    new Thread(new TunnelHandler(client)).start();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void stopServer() {
        isRunning = false;
        btnToggle.setText("ЗАПУСТИТЬ РАЗДАЧУ");
        btnToggle.setBackgroundColor(Color.parseColor("#007ACC"));
        tvStatus.setText("Статус: Остановлен");
        tvStatus.setTextColor(Color.LTGRAY);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    // Обработчик туннеля HTTP и защищенного HTTPS CONNECT
    private static class TunnelHandler implements Runnable {
        private final Socket client;

        public TunnelHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                byte[] buf = new byte[8192];
                int read = in.read(buf);
                if (read <= 0) { client.close(); return; }

                String req = new String(buf, 0, read);
                String[] lines = req.split("\r\n");
                if (lines.length == 0) { client.close(); return; }

                String[] parts = lines[0].split(" ");
                if (parts.length < 2) { client.close(); return; }

                String method = parts[0];
                String target = parts[1];

                String host;
                int port = 80;

                if (method.equalsIgnoreCase("CONNECT")) {
                    // HTTPS туннель (Сайты, INCY VPN, мессенджеры)
                    String[] hp = target.split(":");
                    host = hp[0];
                    if (hp.length > 1) port = Integer.parseInt(hp[1]);

                    Socket remote = new Socket(host, port);
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                    out.flush();

                    pipe(client, remote);
                } else {
                    // Обычный HTTP
                    if (target.startsWith("http://")) target = target.substring(7);
                    int slash = target.indexOf('/');
                    if (slash > 0) target = target.substring(0, slash);
                    String[] hp = target.split(":");
                    host = hp[0];
                    if (hp.length > 1) port = Integer.parseInt(hp[1]);

                    Socket remote = new Socket(host, port);
                    remote.getOutputStream().write(buf, 0, read);
                    pipe(client, remote);
                }
            } catch (Exception ignored) {
                try { client.close(); } catch (Exception ignored2) {}
            }
        }

        private void pipe(Socket a, Socket b) {
            new Thread(() -> forward(a, b)).start();
            forward(b, a);
        }

        private void forward(Socket src, Socket dst) {
            try {
                byte[] b = new byte[16384];
                InputStream in = src.getInputStream();
                OutputStream out = dst.getOutputStream();
                int len;
                while ((len = in.read(b)) != -1) {
                    out.write(b, 0, len);
                    out.flush();
                }
            } catch (Exception ignored) {}
            try { src.close(); } catch (Exception ignored2) {}
            try { dst.close(); } catch (Exception ignored2) {}
        }
    }
}
