package com.amplogik.shuttlepro;

public class ReportParser {
    // byte 3 bitmask (low button byte)
    public static final int F1 = 1 << 0;
    public static final int F2 = 1 << 1;
    public static final int F3 = 1 << 2;
    public static final int F4 = 1 << 3;
    public static final int F5 = 1 << 4;
    public static final int F6 = 1 << 5;
    public static final int F7 = 1 << 6;
    public static final int F8 = 1 << 7;

    // byte 4 bitmask, shifted up 8 bits so the whole button map lives in one int
    public static final int F9 = 1 << 8;
    public static final int B2 = 1 << 9;
    public static final int B3 = 1 << 10;
    public static final int B1 = 1 << 11;
    public static final int B4 = 1 << 12;
    public static final int M1 = 1 << 13;
    public static final int M2 = 1 << 14;

    public int shuttle;      // byte 0, signed, -7..+7, absolute
    public int jogCounter;   // byte 1, unsigned 0..255, wraps
    public int buttons;      // combined bitmap from bytes 3 and 4

    public void parse(byte[] data) {
        shuttle = (byte) data[0];
        jogCounter = data[1] & 0xFF;
        buttons = (data[3] & 0xFF) | ((data[4] & 0xFF) << 8);
    }

    public void copyFrom(ReportParser other) {
        shuttle = other.shuttle;
        jogCounter = other.jogCounter;
        buttons = other.buttons;
    }
}
