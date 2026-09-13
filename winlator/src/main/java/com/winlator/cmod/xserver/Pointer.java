package com.winlator.cmod.xserver;

import com.winlator.cmod.math.Mathf;

import java.util.ArrayList;

public class Pointer {
    public enum Button {
        BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT, BUTTON_SCROLL_UP, BUTTON_SCROLL_DOWN, BUTTON_SCROLL_CLICK_LEFT, BUTTON_SCROLL_CLICK_RIGHT;

        public byte code() {
            return (byte)(ordinal() + 1);
        }

        public int flag() {
            return 1<<(code() + MAX_BUTTONS);
        }
    }
    public static final byte MAX_BUTTONS = 7;
    private final ArrayList<OnPointerMotionListener> onPointerMotionListeners = new ArrayList<>();
    private final Bitmask buttonMask = new Bitmask();
    private short x;
    private short y;

    public interface OnPointerMotionListener {
        default void onPointerButtonPress(Button button) {}
        default void onPointerButtonRelease(Button button) {}
        default void onPointerMove(short x, short y) {}
    }

    /**
     * v2.23 修复：原构造函数依赖 XServer（内置 X 协议服务器，本集成已剔除
     * 显示端改由 AnWind 的 lorie X server 承担），改为无参构造；
     * 仅保留坐标/按键状态与监听器逻辑供 winhandler 等模块复用。
     */
    public Pointer() {}

    public void setX(int x) {
        this.x = (short)x;
    }

    public void setY(int y) {
        this.y = (short)y;
    }

    public short getX() {
        return x;
    }

    public short getY() {
        return y;
    }

    public short getClampedX() {
        return (short)Mathf.clamp(x, 0, Short.MAX_VALUE);
    }

    public short getClampedY() {
        return (short)Mathf.clamp(y, 0, Short.MAX_VALUE);
    }

    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
        triggerOnPointerMove(this.x, this.y);
    }

    public Bitmask getButtonMask() {
        return buttonMask;
    }

    public void setButton(Button button, boolean pressed) {
        boolean oldPressed = isButtonPressed(button);
        buttonMask.set(button.flag(), pressed);
        if (oldPressed != pressed) {
            if (pressed) {
                triggerOnPointerButtonPress(button);
            }
            else triggerOnPointerButtonRelease(button);
        }
    }

    public boolean isButtonPressed(Button button) {
        return buttonMask.isSet(button.flag());
    }

    public void addOnPointerMotionListener(OnPointerMotionListener onPointerMotionListener) {
        onPointerMotionListeners.add(onPointerMotionListener);
    }

    public void removeOnPointerMotionListener(OnPointerMotionListener onPointerMotionListener) {
        onPointerMotionListeners.remove(onPointerMotionListener);
    }

    private void triggerOnPointerButtonPress(Button button) {
        for (int i = onPointerMotionListeners.size()-1; i >= 0; i--) {
            onPointerMotionListeners.get(i).onPointerButtonPress(button);
        }
    }

    private void triggerOnPointerButtonRelease(Button button) {
        for (int i = onPointerMotionListeners.size()-1; i >= 0; i--) {
            onPointerMotionListeners.get(i).onPointerButtonRelease(button);
        }
    }

    private void triggerOnPointerMove(short x, short y) {
        for (int i = onPointerMotionListeners.size()-1; i >= 0; i--) {
            onPointerMotionListeners.get(i).onPointerMove(x, y);
        }
    }
}
