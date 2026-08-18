package com.rc.client.ui;

import com.rc.client.control.InputController;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 远控会话视图：控制端显示远端画面并采集键鼠输入；被控端仅显示状态（画面已推流）。
 * 顶部状态栏 + 断开，底部文件发送 / 麦克风开关（仅被控端）。
 */
public final class RemoteView {

    private final boolean controller;
    private final BorderPane root = new BorderPane();
    private final ImageView video = new ImageView();
    private final StackPane viewport = new StackPane(video);
    private final Label status = new Label("正在建立数据面 ...");
    private final InputController inputController;

    private int remoteWidth = 1;
    private int remoteHeight = 1;
    private boolean audioEnabled = false;

    public RemoteView(boolean controller, Runnable onDisconnect, Consumer<Path> onSendFile,
                      Consumer<Boolean> onAudio, InputController inputController) {
        this.controller = controller;
        this.inputController = inputController;

        Button disconnectBtn = new Button("断开");
        disconnectBtn.setOnAction(e -> onDisconnect.run());

        HBox top = new HBox(10);
        top.setPadding(new Insets(8));
        top.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        top.getChildren().addAll(status, spacer, disconnectBtn);

        StackPane center;
        if (controller) {
            center = viewport;
            video.setPreserveRatio(true);
            video.setSmooth(true);
            viewport.widthProperty().addListener((obs, o, n) -> updateFit());
            viewport.heightProperty().addListener((obs, o, n) -> updateFit());
            if (inputController != null) {
                inputController.attach(video);
            }
        } else {
            Label placeholder = new Label("正在被远程控制（本机画面已共享）");
            placeholder.setStyle("-fx-font-size: 16px;");
            center = new StackPane(placeholder);
        }

        Button sendFileBtn = new Button("发送文件");
        sendFileBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            File f = chooser.showOpenDialog(root.getScene().getWindow());
            if (f != null) {
                onSendFile.accept(f.toPath());
            }
        });
        HBox bottom = new HBox(10, sendFileBtn);
        bottom.setPadding(new Insets(8));
        bottom.setAlignment(Pos.CENTER_LEFT);
        if (!controller) {
            Button audioBtn = new Button("开启麦克风");
            audioBtn.setOnAction(e -> {
                audioEnabled = !audioEnabled;
                audioBtn.setText(audioEnabled ? "关闭麦克风" : "开启麦克风");
                onAudio.accept(audioEnabled);
            });
            bottom.getChildren().add(audioBtn);
        }

        root.setTop(top);
        root.setCenter(center);
        root.setBottom(bottom);
    }

    public Parent root() {
        return root;
    }

    /** 控制端收到新视频帧：转换并缩放适配窗口。 */
    public void onFrame(BufferedImage image) {
        remoteWidth = image.getWidth();
        remoteHeight = image.getHeight();
        video.setImage(SwingFXUtils.toFXImage(image, null));
        updateFit();
    }

    public void showStatus(String message) {
        status.setText(message == null ? "" : message);
    }

    private void updateFit() {
        if (!controller || remoteWidth <= 0 || remoteHeight <= 0) {
            return;
        }
        double cw = viewport.getWidth();
        double ch = viewport.getHeight();
        if (cw <= 0 || ch <= 0) {
            return;
        }
        double scale = Math.min(cw / remoteWidth, ch / remoteHeight);
        video.setFitWidth(remoteWidth * scale);
        video.setFitHeight(remoteHeight * scale);
        if (inputController != null) {
            double s = 1.0 / scale;
            inputController.setScale(s, s);
        }
    }
}
