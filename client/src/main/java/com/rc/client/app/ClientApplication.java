package com.rc.client.app;

import com.rc.client.signaling.SignalingClientConfig;
import com.rc.client.ui.LoginView;
import com.rc.client.ui.MainView;
import com.rc.client.ui.RemoteView;
import com.rc.common.model.Endpoint;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * JavaFX 桌面端入口：视图切换（登录 / 主界面 / 远控视图）+ {@link RemoteControlClient}
 * 门面的 UI 回调桥接（回调线程 → FX 线程）。
 */
public class ClientApplication extends Application implements RemoteControlClient.UiListener {

    private static final int REST_PORT = 8080;
    private static final int SIGNALING_PORT = 8443;

    private Stage stage;
    private RemoteControlClient client;

    private LoginView loginView;
    private MainView mainView;
    private RemoteView remoteView;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("RC Client");
        showLogin();
    }

    @Override
    public void stop() {
        if (client != null) {
            client.shutdown();
        }
    }

    // ---------- 视图切换 ----------

    private void showLogin() {
        loginView = new LoginView(this::doLogin, this::doRegister);
        setScene(loginView.root());
    }

    private void showMain() {
        mainView = new MainView(this::setConnectPassword, this::doConnect);
        mainView.setDeviceCode(client == null ? "" : client.deviceCode());
        setScene(mainView.root());
    }

    private void showRemote(boolean controller) {
        RemoteView view = new RemoteView(controller, this::doHangup, this::doSendFile, this::doToggleAudio,
                controller ? client.inputController() : null);
        remoteView = view;
        setScene(view.root());
    }

    private void setScene(Parent root) {
        stage.setScene(new Scene(root, 760, 540));
        stage.show();
    }

    // ---------- 动作（阻塞网络调用放后台线程，避免卡 UI） ----------

    private void doLogin(String host, String username, String password) {
        runAsync(() -> {
            try {
                RemoteControlClient c = buildClient(host);
                c.login(username, password);
                client = c;
                c.start(); // 触发 onDeviceReady → showMain
            } catch (Exception e) {
                Platform.runLater(() -> loginView.showError(e.getMessage()));
            }
        });
    }

    private void doRegister(String host, String username, String password) {
        runAsync(() -> {
            try {
                RemoteControlClient c = buildClient(host);
                c.register(username, password);
                c.login(username, password);
                client = c;
                c.start();
            } catch (Exception e) {
                Platform.runLater(() -> loginView.showError(e.getMessage()));
            }
        });
    }

    private void setConnectPassword(String password) {
        if (client != null) {
            client.setConnectPassword(password);
        }
    }

    private void doConnect(String remoteCode, String remotePassword) {
        if (client != null) {
            client.connect(remoteCode, remotePassword);
        }
    }

    private void doHangup() {
        if (client != null) {
            client.hangup();
        }
    }

    private void doSendFile(Path file) {
        if (client != null) {
            client.sendFile(file);
        }
    }

    private void doToggleAudio(boolean enabled) {
        if (client != null) {
            client.setAudioCaptureEnabled(enabled);
        }
    }

    private RemoteControlClient buildClient(String host) {
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        String restScheme = System.getProperty("rc.client.rest-scheme", loopback ? "http" : "https");
        String baseUrl = restScheme + "://" + host + ":" + REST_PORT;
        SignalingClientConfig sc = new SignalingClientConfig();
        sc.setHost(host);
        sc.setPort(SIGNALING_PORT);
        sc.setTls(true);      // 信令默认 TLS；dev 自签
        // Never silently disable certificate verification for a remote production host.
        sc.setTrustAll(Boolean.parseBoolean(System.getProperty(
                "rc.client.trust-all", Boolean.toString(loopback))));
        List<Endpoint> stun = List.of(new Endpoint("stun.l.google.com", 19302));
        Path receiveDir = Path.of(System.getProperty("user.home"), "rc-received");
        return new RemoteControlClient(baseUrl, sc, stun, receiveDir, this);
    }

    private static void runAsync(Runnable r) {
        Thread t = new Thread(r, "rc-ui-task");
        t.setDaemon(true);
        t.start();
    }

    // ---------- UiListener（切回 FX 线程） ----------

    @Override
    public void onState(String message) {
        Platform.runLater(() -> {
            if (remoteView != null) {
                remoteView.showStatus(message);
            }
        });
    }

    @Override
    public void onDeviceReady(String deviceCode) {
        Platform.runLater(this::showMain);
    }

    @Override
    public void onSessionConnected(boolean controller) {
        Platform.runLater(() -> showRemote(controller));
    }

    @Override
    public void onVideoFrame(BufferedImage image) {
        Platform.runLater(() -> {
            if (remoteView != null) {
                remoteView.onFrame(image);
            }
        });
    }

    @Override
    public void onInvite(String controllerDeviceCode, long sessionId) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("远程控制请求");
            alert.setHeaderText("设备 " + controllerDeviceCode + " 请求控制本机");
            alert.setContentText("是否接受此次远程控制？");
            Optional<ButtonType> result = alert.showAndWait();
            if (client != null) {
                client.acceptInvite(sessionId, result.isPresent() && result.get() == ButtonType.OK);
            }
        });
    }

    @Override
    public void onSessionEnded(String reason) {
        Platform.runLater(() -> {
            showMain();
            showInfo("会话结束", reason);
        });
    }

    @Override
    public void onError(String message) {
        Platform.runLater(() -> showInfo("错误", message));
    }

    @Override
    public void onPathChanged(String pathType) {
        Platform.runLater(() -> {
            if (remoteView != null) {
                remoteView.showStatus("路径切换: " + pathType);
            }
        });
    }

    @Override
    public void onFileEvent(String message) {
        Platform.runLater(() -> {
            if (remoteView != null) {
                remoteView.showStatus(message);
            }
        });
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(message == null ? "" : message);
        alert.showAndWait();
    }
}
