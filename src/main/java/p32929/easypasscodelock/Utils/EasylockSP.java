package p32929.easypasscodelock.Utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import eu.siacs.conversations.AppSettings;

/**
 * Created by p32929 on 7/17/2018.
 */

public class EasylockSP {
    public static SharedPreferences sharedPreferences;

    //
    public static void init(Context context) {
        if (sharedPreferences == null) {
            try {
                AppSettings appSettings = new AppSettings(context);
                sharedPreferences = appSettings.getEncryptedPreferences("Lockscreen");

                SharedPreferences normalPrefs = context.getSharedPreferences("Lockscreen", Context.MODE_PRIVATE);
                String password = normalPrefs.getString("password", null);
                if (password != null) {
                    sharedPreferences.edit().putString("password", password).commit();
                    normalPrefs.edit().remove("password").commit();
                }
            } catch (Exception e) {
                throw new RuntimeException("Could not initialize encrypted preferences for Lockscreen", e);
            }
        }
    }

    //
    @SuppressLint("ApplySharedPref")
    public static void put(String title, boolean value) {
        sharedPreferences.edit().putBoolean(title, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public static void put(String title, float value) {
        sharedPreferences.edit().putFloat(title, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public static void put(String title, int value) {
        sharedPreferences.edit().putInt(title, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public static void put(String title, long value) {
        sharedPreferences.edit().putLong(title, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public static void put(String title, String value) {
        sharedPreferences.edit().putString(title, value).commit();
    }

    //
    public static boolean getBoolean(String title, boolean defaultValue) {
        return sharedPreferences.getBoolean(title, defaultValue);
    }

    public static float getFloat(String title, float defaultValue) {
        return sharedPreferences.getFloat(title, defaultValue);
    }

    public static int getInt(String title, int defaultValue) {
        return sharedPreferences.getInt(title, defaultValue);
    }

    public static long getLong(String title, long defaultValue) {
        return sharedPreferences.getLong(title, defaultValue);
    }

    public static String getString(String title, String defaultValue) {
        return sharedPreferences.getString(title, defaultValue);
    }

    //
    public static void clearAll() {
        sharedPreferences.edit().clear().commit();
    }

}
