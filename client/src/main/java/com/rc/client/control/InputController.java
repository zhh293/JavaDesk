package com.rc.client.control;

import com.rc.client.transport.TransportChannel;
import com.rc.common.constant.ChannelType;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制端输入采集：挂到 JavaFX 远控视图节点上，捕获鼠标 / 键盘事件，
 * 经 {@link ControlCodec} 编码后通过 {@link ChannelType#CONTROL} 通道推给被控端。
 *
 * <p>坐标按 {@link #setScale} 缩放到远端屏幕像素；键码经 JavaFX → AWT 映射。
 * 仅覆盖字母 / 数字 / 常见命名键与组合键（修饰键单独下发，远端自然合成符号）。</p>
 */
public final class InputController {

    private static final Logger log = LoggerFactory.getLogger(InputController.class);

    private final TransportChannel channel;
    private volatile double scaleX = 1.0;
    private volatile double scaleY = 1.0;
    private volatile boolean enabled = true;

    public InputController(TransportChannel channel) {
        this.channel = channel;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 设置视图 → 远端屏幕的坐标缩放比（远端宽 / 视图宽）。 */
    public void setScale(double scaleX, double scaleY) {
        this.scaleX = scaleX > 0 ? scaleX : 1.0;
        this.scaleY = scaleY > 0 ? scaleY : 1.0;
    }

    /** 绑定鼠标 / 键盘事件到视图节点。 */
    public void attach(Node node) {
        node.setFocusTraversable(true);
        node.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onMouseMoved);
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        node.addEventHandler(ScrollEvent.SCROLL, this::onScroll);
        node.addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        node.addEventHandler(KeyEvent.KEY_RELEASED, this::onKeyReleased);
    }

    public void detach(Node node) {
        node.removeEventHandler(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
        node.removeEventHandler(MouseEvent.MOUSE_DRAGGED, this::onMouseMoved);
        node.removeEventHandler(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        node.removeEventHandler(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        node.removeEventHandler(ScrollEvent.SCROLL, this::onScroll);
        node.removeEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        node.removeEventHandler(KeyEvent.KEY_RELEASED, this::onKeyReleased);
    }

    private void onMouseMoved(MouseEvent e) {
        if (!enabled) {
            return;
        }
        channel.send(ChannelType.CONTROL, ControlCodec.mouseMove(scaledX(e.getX()), scaledY(e.getY())));
    }

    private void onMousePressed(MouseEvent e) {
        if (!enabled) {
            return;
        }
        channel.send(ChannelType.CONTROL, ControlCodec.mousePress(buttonOf(e.getButton()), scaledX(e.getX()), scaledY(e.getY())));
    }

    private void onMouseReleased(MouseEvent e) {
        if (!enabled) {
            return;
        }
        channel.send(ChannelType.CONTROL, ControlCodec.mouseRelease(buttonOf(e.getButton()), scaledX(e.getX()), scaledY(e.getY())));
    }

    private void onScroll(ScrollEvent e) {
        if (!enabled) {
            return;
        }
        int rotation = (int) Math.round(e.getDeltaY() / 40.0);
        if (rotation != 0) {
            channel.send(ChannelType.CONTROL, ControlCodec.mouseWheel(rotation));
        }
    }

    private void onKeyPressed(KeyEvent e) {
        if (!enabled) {
            return;
        }
        int code = toAwt(e.getCode());
        if (code != java.awt.event.KeyEvent.VK_UNDEFINED) {
            channel.send(ChannelType.CONTROL, ControlCodec.keyPress(code));
            e.consume();
        }
    }

    private void onKeyReleased(KeyEvent e) {
        if (!enabled) {
            return;
        }
        int code = toAwt(e.getCode());
        if (code != java.awt.event.KeyEvent.VK_UNDEFINED) {
            channel.send(ChannelType.CONTROL, ControlCodec.keyRelease(code));
            e.consume();
        }
    }

    private int scaledX(double x) {
        return (int) Math.round(x * scaleX);
    }

    private int scaledY(double y) {
        return (int) Math.round(y * scaleY);
    }

    private static int buttonOf(MouseButton b) {
        return switch (b) {
            case PRIMARY -> ControlCodec.BUTTON_LEFT;
            case MIDDLE -> ControlCodec.BUTTON_MIDDLE;
            case SECONDARY -> ControlCodec.BUTTON_RIGHT;
            default -> ControlCodec.BUTTON_LEFT;
        };
    }

    /** JavaFX KeyCode → AWT {@code KeyEvent.VK_*}；不可映射返回 {@code VK_UNDEFINED}。 */
    private static int toAwt(KeyCode code) {
        if (code.isLetterKey() || code.isDigitKey()) {
            return java.awt.event.KeyEvent.getExtendedKeyCodeForChar(code.getName().charAt(0));
        }
        return switch (code) {
            case ENTER -> java.awt.event.KeyEvent.VK_ENTER;
            case BACK_SPACE -> java.awt.event.KeyEvent.VK_BACK_SPACE;
            case TAB -> java.awt.event.KeyEvent.VK_TAB;
            case SPACE -> java.awt.event.KeyEvent.VK_SPACE;
            case ESCAPE -> java.awt.event.KeyEvent.VK_ESCAPE;
            case DELETE -> java.awt.event.KeyEvent.VK_DELETE;
            case INSERT -> java.awt.event.KeyEvent.VK_INSERT;
            case HOME -> java.awt.event.KeyEvent.VK_HOME;
            case END -> java.awt.event.KeyEvent.VK_END;
            case PAGE_UP -> java.awt.event.KeyEvent.VK_PAGE_UP;
            case PAGE_DOWN -> java.awt.event.KeyEvent.VK_PAGE_DOWN;
            case UP -> java.awt.event.KeyEvent.VK_UP;
            case DOWN -> java.awt.event.KeyEvent.VK_DOWN;
            case LEFT -> java.awt.event.KeyEvent.VK_LEFT;
            case RIGHT -> java.awt.event.KeyEvent.VK_RIGHT;
            case SHIFT -> java.awt.event.KeyEvent.VK_SHIFT;
            case CONTROL -> java.awt.event.KeyEvent.VK_CONTROL;
            case ALT -> java.awt.event.KeyEvent.VK_ALT;
            case META -> java.awt.event.KeyEvent.VK_META;
            case CAPS -> java.awt.event.KeyEvent.VK_CAPS_LOCK;
            case NUM_LOCK -> java.awt.event.KeyEvent.VK_NUM_LOCK;
            case SCROLL_LOCK -> java.awt.event.KeyEvent.VK_SCROLL_LOCK;
            case PAUSE -> java.awt.event.KeyEvent.VK_PAUSE;
            case PRINTSCREEN -> java.awt.event.KeyEvent.VK_PRINTSCREEN;
            case F1 -> java.awt.event.KeyEvent.VK_F1;
            case F2 -> java.awt.event.KeyEvent.VK_F2;
            case F3 -> java.awt.event.KeyEvent.VK_F3;
            case F4 -> java.awt.event.KeyEvent.VK_F4;
            case F5 -> java.awt.event.KeyEvent.VK_F5;
            case F6 -> java.awt.event.KeyEvent.VK_F6;
            case F7 -> java.awt.event.KeyEvent.VK_F7;
            case F8 -> java.awt.event.KeyEvent.VK_F8;
            case F9 -> java.awt.event.KeyEvent.VK_F9;
            case F10 -> java.awt.event.KeyEvent.VK_F10;
            case F11 -> java.awt.event.KeyEvent.VK_F11;
            case F12 -> java.awt.event.KeyEvent.VK_F12;
            case MINUS -> java.awt.event.KeyEvent.VK_MINUS;
            case EQUALS -> java.awt.event.KeyEvent.VK_EQUALS;
            case COMMA -> java.awt.event.KeyEvent.VK_COMMA;
            case PERIOD -> java.awt.event.KeyEvent.VK_PERIOD;
            case SLASH -> java.awt.event.KeyEvent.VK_SLASH;
            case SEMICOLON -> java.awt.event.KeyEvent.VK_SEMICOLON;
            case QUOTE -> java.awt.event.KeyEvent.VK_QUOTE;
            case OPEN_BRACKET -> java.awt.event.KeyEvent.VK_OPEN_BRACKET;
            case CLOSE_BRACKET -> java.awt.event.KeyEvent.VK_CLOSE_BRACKET;
            case BACK_SLASH -> java.awt.event.KeyEvent.VK_BACK_SLASH;
            case BACK_QUOTE -> java.awt.event.KeyEvent.VK_BACK_QUOTE;
            default -> java.awt.event.KeyEvent.VK_UNDEFINED;
        };
    }
}
