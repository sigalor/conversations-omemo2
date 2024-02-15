package de.monocles.chat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import android.preference.PreferenceManager;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.util.IntroHelper;
public class StartUI extends AppCompatActivity {

    private static final long LOCK_TIME = 30_000;
    private static boolean lockedPreference = false;
    private static boolean isLocked = true;
    private static long lastInteraction = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final var prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        lockedPreference = prefs.getBoolean("pref_key_lock", false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_ui);
        IntroHelper.showIntro(this, false);
        String PREF_FIRST_START = "FirstStart";
        SharedPreferences FirstStart = getApplicationContext().getSharedPreferences(PREF_FIRST_START, Context.MODE_PRIVATE);
        long FirstStartTime = FirstStart.getLong(PREF_FIRST_START, 0);
        Log.d(Config.LOGTAG, "Starting " + getString(R.string.app_name) + "(" + FirstStartTime + ")");
        Intent intent = new Intent(this, ConversationsActivity.class);
        intent.putExtra(PREF_FIRST_START, FirstStartTime);
        startActivity(intent);
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
        finish();    }


    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public static void setLockedPreference(boolean lockedPreference) {
        Log.i("App lock", "New locked preference: " + lockedPreference);
        StartUI.lockedPreference = lockedPreference;
    }

    public static boolean isLocked() {
        if (!isLocked && System.currentTimeMillis() > (LOCK_TIME + lastInteraction)) {
            isLocked = true;
        }
        return lockedPreference && isLocked;
    }

    public static void unlock() {
        isLocked = false;
    }

    public static void updateLastInteraction() {
        lastInteraction = System.currentTimeMillis();
    }
}
