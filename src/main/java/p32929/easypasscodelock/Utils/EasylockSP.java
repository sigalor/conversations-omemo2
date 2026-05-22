package p32929.easypasscodelock.Utils;

import android.content.Context;

public class EasylockSP {

    private static LockscreenStorage storage;

    public static void init(Context context) {
        if (storage == null) {
            storage = new LockscreenStorage(context);
        }
    }

    public static void put(String title, String value) {
        if ("password".equals(title)) {
            storage.writePin(value);
        }
    }

    public static String getString(String title, String defaultValue) {
        if ("password".equals(title)) {
            final String pin = storage.readPin();
            return pin != null ? pin : defaultValue;
        }
        return defaultValue;
    }

    public static void clearAll() {
        storage.writePin(null);
    }
}
