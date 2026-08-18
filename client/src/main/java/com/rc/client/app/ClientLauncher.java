package com.rc.client.app;

import javafx.application.Application;

/**
 * 客户端启动入口。独立于 {@link Application} 子类，规避 JavaFX 模块启动检查。
 */
public final class ClientLauncher {

    private ClientLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(ClientApplication.class, args);
    }
}
