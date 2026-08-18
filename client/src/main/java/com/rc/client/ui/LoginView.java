package com.rc.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 登录 / 注册视图：输入服务器地址、账号、密码。
 */
public final class LoginView {

    private final VBox root = new VBox(12);
    private final TextField host = new TextField("127.0.0.1");
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label status = new Label();

    public LoginView(TriAction onLogin, TriAction onRegister) {
        root.setPadding(new Insets(28));
        root.setAlignment(Pos.CENTER);

        Label title = new Label("RC Client");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.addRow(0, new Label("服务器地址"), host);
        grid.addRow(1, new Label("用户名"), username);
        grid.addRow(2, new Label("密码"), password);

        Button loginBtn = new Button("登录");
        loginBtn.setDefaultButton(true);
        Button registerBtn = new Button("注册");
        HBox buttons = new HBox(8, loginBtn, registerBtn);
        buttons.setAlignment(Pos.CENTER);

        loginBtn.setOnAction(e -> onLogin.accept(host.getText().trim(), username.getText().trim(), password.getText()));
        registerBtn.setOnAction(e -> onRegister.accept(host.getText().trim(), username.getText().trim(), password.getText()));

        status.setStyle("-fx-text-fill: red;");

        root.getChildren().addAll(title, grid, buttons, status);
    }

    public Parent root() {
        return root;
    }

    public void showError(String message) {
        status.setText(message == null ? "" : message);
    }

    @FunctionalInterface
    public interface TriAction {
        void accept(String host, String username, String password);
    }
}
