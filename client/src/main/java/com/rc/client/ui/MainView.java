package com.rc.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 主界面：展示本机设备码 + 设置本机连接密码，并输入远端设备码发起连接。
 */
public final class MainView {

    private final VBox root = new VBox(14);
    private final Label deviceCode = new Label();
    private final PasswordField localPassword = new PasswordField();
    private final TextField remoteCode = new TextField();
    private final PasswordField remotePassword = new PasswordField();
    private final Label status = new Label();

    public MainView(Consumer<String> onLocalPassword, BiConsumer<String, String> onConnect) {
        root.setPadding(new Insets(24));
        root.setAlignment(Pos.CENTER);

        Label title = new Label("设备信息");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Button copyBtn = new Button("复制");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(deviceCode.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        HBox codeRow = new HBox(8, deviceCode, copyBtn);
        codeRow.setAlignment(Pos.CENTER);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.addRow(0, new Label("本机设备码"), codeRow);
        grid.addRow(1, new Label("本机连接密码"), localPassword);
        grid.addRow(2, new Label("远端设备码"), remoteCode);
        grid.addRow(3, new Label("远端连接密码"), remotePassword);

        Button connectBtn = new Button("连接");
        connectBtn.setDefaultButton(true);
        connectBtn.setMaxWidth(Double.MAX_VALUE);

        localPassword.textProperty().addListener((obs, o, n) -> onLocalPassword.accept(n));
        connectBtn.setOnAction(e -> onConnect.accept(remoteCode.getText().trim(), remotePassword.getText()));

        status.setStyle("-fx-text-fill: #666;");

        root.getChildren().addAll(title, grid, connectBtn, status);
    }

    public Parent root() {
        return root;
    }

    public void setDeviceCode(String code) {
        deviceCode.setText(code);
        deviceCode.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
    }

    public void showStatus(String message) {
        status.setText(message == null ? "" : message);
    }
}
