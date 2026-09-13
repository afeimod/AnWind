package com.winlator.cmod;

/**
 * PreferenceKeys —— 外置手柄（ExternalController）SharedPreferences 键名常量。
 *
 * 与 Winlator Cmod 上游保持一致的键名，保证用户偏好（死区 / 灵敏度 /
 * 摇杆反转 / 方形死区）在升级后仍然可读。
 */
public class PreferenceKeys {
    public static final String DEADZONE_LEFT = "deadzone_left";
    public static final String DEADZONE_RIGHT = "deadzone_right";
    public static final String SENSITIVITY_LEFT = "sensitivity_left";
    public static final String SENSITIVITY_RIGHT = "sensitivity_right";
    public static final String INVERT_LEFT_X = "invert_left_x";
    public static final String INVERT_LEFT_Y = "invert_left_y";
    public static final String INVERT_RIGHT_X = "invert_right_x";
    public static final String INVERT_RIGHT_Y = "invert_right_y";
    public static final String SQUARE_DEADZONE_LEFT = "square_deadzone_left";
}
