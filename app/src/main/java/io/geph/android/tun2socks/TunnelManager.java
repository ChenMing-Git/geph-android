package io.geph.android.tun2socks;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.v4.app.NotificationCompat;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.Buffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.geph.android.MainActivity;
import io.geph.android.R;

public class TunnelManager  {

    public static final int NOTIFICATION_ID = 7839214;

    public static final String SOCKS_SERVER_ADDRESS_BASE = "socksServerAddress";
    public static final String SOCKS_SERVER_PORT_EXTRA = "socksServerPort";
    public static final String DNS_SERVER_PORT_EXTRA = "dnsServerPort";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String EXIT_NAME = "exitName";
    public static final String LISTEN_ALL = "listenAll";
    public static final String FORCE_BRIDGES = "forceBridges";
    public static final String BYPASS_CHINESE = "bypassChinese";
    public static final String USE_TCP = "useTCP";
    public static final String EXCLUDE_APPS_JSON = "excludeAppsJson";

    private static final String LOG_TAG = "TunnelManager";
    private static final String CACHE_DIR_NAME = "geph";
    private static final String DAEMON_IN_NATIVELIB_DIR = "libgeph.so";
    private TunnelVpnService m_parentService = null;
    private CountDownLatch m_tunnelThreadStopSignal;
    private Thread m_tunnelThread;
    private AtomicBoolean m_isStopping;
    private String mSocksServerAddressBase;
    private String mSocksServerAddress;
    private String mSocksServerPort;
    private String mDnsServerPort;
    private String mDnsResolverAddress;
    private String mUsername;
    private String mPassword;
    private String mExitName;
    private Boolean mListenAll;
    private Boolean mForceBridges;
    private Boolean mBypassChinese;
    private Boolean mUseTCP;
    private String mExcludeAppsJson;
    private Process mDaemonProc;
    private AtomicBoolean m_isReconnecting;

    public TunnelManager(TunnelVpnService parentService) {
        m_parentService = parentService;
        m_isStopping = new AtomicBoolean(false);
        m_isReconnecting = new AtomicBoolean(false);
    }

    // Implementation of android.app.Service.onStartCommand
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.i(LOG_TAG, "labooyah");
            return 0;
        }
        Log.i(LOG_TAG, "onStartCommand");

        mSocksServerAddressBase = intent.getStringExtra(SOCKS_SERVER_ADDRESS_BASE);
        mSocksServerPort = intent.getStringExtra(SOCKS_SERVER_PORT_EXTRA);
        mSocksServerAddress = mSocksServerAddressBase + ":" + mSocksServerPort;
        mDnsServerPort = intent.getStringExtra(DNS_SERVER_PORT_EXTRA);
        mDnsResolverAddress = mSocksServerAddressBase + ":" + mDnsServerPort;
        Log.i(LOG_TAG, "onStartCommand parsed some stuff");
        mUsername = intent.getStringExtra(USERNAME);
        mPassword = intent.getStringExtra(PASSWORD);
        mExitName = intent.getStringExtra(EXIT_NAME);
        mForceBridges = intent.getBooleanExtra(FORCE_BRIDGES, false);
        mListenAll = intent.getBooleanExtra(LISTEN_ALL, false);
        mBypassChinese = intent.getBooleanExtra(BYPASS_CHINESE, false);
        mUseTCP = intent.getBooleanExtra(USE_TCP, false);
        mExcludeAppsJson = intent.getStringExtra(EXCLUDE_APPS_JSON);
        Log.i(LOG_TAG, "onStartCommand parsed intent");

        if (setupAndRunDaemon() == null) {
            Log.e(LOG_TAG, "Failed to start the socks proxy daemon.");
            m_parentService.broadcastVpnStart(false /* success */);
            return 0;
        }

        if (mSocksServerAddress == null) {
            Log.e(LOG_TAG, "Failed to receive the socks server address.");
            m_parentService.broadcastVpnStart(false /* success */);
            return 0;
        }

        final Context ctx = getContext();

        Intent notificationIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, 0, notificationIntent, 0);

        Bitmap largeIcon = BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_launcher);
        String channelId = createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, createNotificationChannel())
                .setSmallIcon(R.drawable.ic_stat_notification_icon)
                .setLargeIcon(largeIcon)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(ctx.getText(R.string.notification_label))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        Notification notification = builder.build();

        // starting this service on foreground to avoid accidental GC by Android system
        getVpnService().startForeground(NOTIFICATION_ID, notification);

        return Service.START_STICKY;
    }

    private String createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "geph_service";
            String channelName = "Geph background service";
            NotificationChannel chan = new NotificationChannel(channelId,
                    channelName, NotificationManager.IMPORTANCE_NONE);
            chan.setDescription("Geph background service");
            NotificationManager notificationManager = getContext().getSystemService(NotificationManager.class);
            assert notificationManager != null;
            notificationManager.createNotificationChannel(chan);
            return channelId;
        }
        return "";
    }

    private Process setupAndRunDaemon() {
        // Run Geph-Go with uProxy style fail-safe
        mDaemonProc = runDaemon();
        if (mDaemonProc == null) {
            return mDaemonProc;
        }

        Thread daemonLogs = new Thread(new Runnable() {
            @Override
            public void run() {
                String tag = "Geph";
                InputStream err = mDaemonProc.getErrorStream();
                Scanner scanner = new Scanner(err);
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    Log.d(tag, line);
                }
                Log.d(tag, "stopping log stuff because the process died");
                getVpnService().stopForeground(true);
                System.exit(0);
            }
        });
        daemonLogs.start();

        Thread daemonStdout = new Thread(new Runnable() {
            @Override
            public void run() {
                final String tag = "GephStdio";
                ParcelFileDescriptor tunFd = null;
                FileInputStream input = null;
                FileOutputStream output = null;
                try {
                    try (InputStream stdout = new BufferedInputStream(mDaemonProc.getInputStream(), 1048576); OutputStream stdin = mDaemonProc.getOutputStream()) {
                        Thread daemonStdin = null;
                        byte[] body = new byte[2048];
                        while (true) {
                            int kind = stdout.read();
                            int bodylen1 = stdout.read();
                            int bodylen2 = stdout.read();
                            int bodylen = bodylen1 + bodylen2*256;
                            int n = 0;
                            while (n < bodylen) {
                                n += stdout.read(body, n, bodylen - n);
                            }
                            if (kind == 1) {
                                String bodyString = new String(Arrays.copyOfRange(body, 0, bodylen), StandardCharsets.UTF_8);
                                Log.d(tag, "init IP address of " + bodyString);
                                String[] splitted = bodyString.split("/");
                                InetAddress addr = InetAddress.getByName(splitted[0]);
                                if (tunFd != null) {
                                    tunFd.close();
                                    daemonStdin.interrupt();
                                    daemonStdin.join();
                                    tunFd = null;
                                }
                                while (tunFd == null) {
                                    VpnService.Builder builder = m_parentService.newBuilder().addAddress(addr, 10).
                                            addRoute("0.0.0.0", 0).
                                            addDnsServer("1.1.1.1").
                                            addDisallowedApplication(getContext().getPackageName());
                                    JSONArray excludedApps = new JSONArray(mExcludeAppsJson);
                                    for (int i = 0; i < excludedApps.length(); i++) {
                                        String packageName = excludedApps.getString(i);
                                        builder.addDisallowedApplication(packageName);
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        builder.setMetered(false);
                                    }
                                    tunFd = builder.setBlocking(true)
                                            .setMtu(1280)
                                            .establish();
                                }
                                input = new FileInputStream(tunFd.getFileDescriptor());
                                output = new FileOutputStream(tunFd.getFileDescriptor());
                                final FileInputStream finalInput = input;
                                daemonStdin = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        byte[] body = new byte[2048];
                                        try {
                                            while (true) {
                                                int n = finalInput.read(body, 3, 2045);
                                                body[0] = 0;
                                                body[1] = (byte) (n % 256);
                                                body[2] = (byte) (n / 256);
                                                stdin.write(body, 0, n+3);
                                                stdin.flush();
//                                                    Log.d(tag, "write to stdin " + n);
                                            }
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                            Log.i(tag, "daemonStdin exited " + e.toString());
                                        }
                                    }
                                });
                                daemonStdin.start();
                            } else {
                                if (output != null) {
                                    try {
//                                        Log.d(tag, "read from stdout " + bodylen);
                                        output.write(body, 0, bodylen);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    }
                } catch (IOException | PackageManager.NameNotFoundException | InterruptedException | JSONException e) {
                    e.printStackTrace();
                }
                finally {
                    if (tunFd != null) {
                        try {
                            tunFd.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
        daemonStdout.start();

        return mDaemonProc;
    }

    private Process runDaemon() {
        final Context ctx = getContext();
        final String daemonBinaryPath =
                ctx.getApplicationInfo().nativeLibraryDir + "/" + DAEMON_IN_NATIVELIB_DIR;
        final String dbPath = ctx.getApplicationInfo().dataDir + "/geph4-credentials-ng";

        try {
//            Os.setenv("RUST_LOG", "debug,sosistab", true);
            List<String> commands = new ArrayList<>();
            commands.add(daemonBinaryPath);
            commands.add("connect");
            commands.add("--username");
            commands.add(mUsername);
            commands.add("--password");
            commands.add(mPassword);
            commands.add("--exit-server");
            commands.add(mExitName);
            commands.add("--credential-cache");
            commands.add(dbPath);
            commands.add("--dns-listen");
            commands.add("127.0.0.1:15353");
            commands.add("--stdio-vpn");
            commands.add("--log-file");
            commands.add(ctx.getApplicationInfo().dataDir + "/logs.txt");
//            commands.add("-fakeDNS=true");
//            commands.add("-dnsAddr=127.0.0.1:49983");
            if (mListenAll) {
                commands.add("--socks5-listen");
                commands.add("0.0.0.0:9909");
                commands.add("--http-listen");
                commands.add("0.0.0.0:9910");
            }
            if (mForceBridges) {
                commands.add("--use-bridges");
            }
            if (mBypassChinese) {
                commands.add("--exclude-prc");
            }
            if (mUseTCP) {
                commands.add("--use-tcp");
            }
            Log.i(LOG_TAG, commands.toString());
            ProcessBuilder pb = new ProcessBuilder(commands);

            return pb.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void terminateSocksProxyDaemon() {
        if (mDaemonProc != null) {
            mDaemonProc.destroy();
            mDaemonProc = null;
        }
    }

    public void restartSocksProxyDaemon() {
        terminateSocksProxyDaemon();
        setupAndRunDaemon();
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy() {
        terminateSocksProxyDaemon();

        if (m_tunnelThread == null) {
            return;
        }

        // signalStopService should have been called, but in case is was not, call here.
        // If signalStopService was not already called, the join may block the calling
        // thread for some time.
        signalStopService();

        try {
            m_tunnelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        m_tunnelThreadStopSignal = null;
        m_tunnelThread = null;

        // stop the foreground service
        getVpnService().stopForeground(true);
    }

    // Signals the runTunnel thread to stopUi. The thread will self-stopUi the service.
    // This is the preferred method for stopping the tunnel service:
    // 1. VpnService doesn't respond to stopService calls
    // 2. The UI will not block while waiting for stopService to return
    public void signalStopService() {
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal.countDown();
        }
    }

    // Stops the tunnel thread and restarts it with |socksServerAddress|.
    public void restartTunnel(final String socksServerAddress, String socksServerPort, String dnsServerPort) {
        Log.i(LOG_TAG, "Restarting tunnel.");
        if (socksServerAddress == null ||
                socksServerAddress.equals(mSocksServerAddress)) {
            // Don't reconnect if the socks server address hasn't changed.
            m_parentService.broadcastVpnStart(true /* success */);
            return;
        }
        mSocksServerAddress = socksServerAddress;
        mSocksServerPort = socksServerPort;
        mDnsServerPort = dnsServerPort;

        m_isReconnecting.set(true);

        // Signaling stopUi to the tunnel thread with the reconnect flag set causes
        // the thread to stopUi the tunnel (but not the VPN or the service) and send
        // the new SOCKS server address to the DNS resolver before exiting itself.
        // When the DNS broadcasts its local address, the tunnel will restart.
        signalStopService();
    }


    //----------------------------------------------------------------------------
    // Tunnel.HostService
    //----------------------------------------------------------------------------


    public Context getContext() {
        return m_parentService;
    }

    public VpnService getVpnService() {
        return ((TunnelVpnService) m_parentService);
    }

    public VpnService.Builder newVpnServiceBuilder() {
        return ((TunnelVpnService) m_parentService).newBuilder();
    }

    public void onDiagnosticMessage(String message) {
        Log.d(LOG_TAG, message);
    }

    public void onTunnelConnected() {
        Log.i(LOG_TAG, "Tunnel connected.");
    }

    @TargetApi(Build.VERSION_CODES.M)
    public void onVpnEstablished() {
        Log.i(LOG_TAG, "VPN established.");
    }
}