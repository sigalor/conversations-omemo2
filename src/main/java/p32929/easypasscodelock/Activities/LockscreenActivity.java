package p32929.easypasscodelock.Activities;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileHelper;
import p32929.easypasscodelock.Interfaces.ActivityChanger;
import eu.siacs.conversations.R;
import p32929.easypasscodelock.Utils.EasyLock;
import p32929.easypasscodelock.Utils.EasylockSP;
import p32929.easypasscodelock.Utils.LockscreenHandler;

public class LockscreenActivity extends LockscreenHandler implements ActivityChanger {

    @SuppressWarnings("rawtypes")
    public static Class activityClassToGo;
    private final int[] passButtonIds = {
            R.id.lbtn1,
            R.id.lbtn2,
            R.id.lbtn3,
            R.id.lbtn4,
            R.id.lbtn5,
            R.id.lbtn6,
            R.id.lbtn7,
            R.id.lbtn8,
            R.id.lbtn9,
            R.id.lbtn0
    };
    //
    private final String checkStatus = "check";
    private final String setStatus = "set";
    private final String setStatus1 = "set1";
    private final String changeStatus = "change";
    private final String changeStatus1 = "change1";
    private final String changeStatus2 = "change2";
    private char[] tempPass = null;
    private TextView textViewDot;
    private TextView textViewHAHA;
    private char[] passBuffer = new char[8];
    private int passBufferLength = 0;
    private String status = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_easy_lockscreen);

        EasylockSP.init(this);
        initViews();

        status = getIntent().getExtras().getString("passStatus", "check");
        if (status.equals(setStatus))
            textViewHAHA.setText(R.string.enter_a_new_password_txt);
        String disableStatus = "disable";
        if (status.equals(disableStatus)) {
            EasylockSP.put("password", (String) null);
            Toast.makeText(this, getString(R.string.password_disabled_txt), Toast.LENGTH_SHORT).show();
            gotoActivity();
        }
    }

    private void initViews() {
        textViewHAHA = findViewById(R.id.span_text);
        textViewDot = findViewById(R.id.dotText);
        TextView textViewForgotPassword = findViewById(R.id.forgot_pass_textview);
        ImageButton buttonEnter = findViewById(R.id.lbtnEnter);
        ImageButton imageButtonDelete = findViewById(R.id.lbtnDelete);

        textViewForgotPassword.setOnClickListener(EasyLock.onClickListener);

        imageButtonDelete.setOnClickListener(view -> {
            if (passBufferLength > 0) {
                passBufferLength--;
                passBuffer[passBufferLength] = '\0';
            }
            updateDotText();
        });

        buttonEnter.setOnClickListener(view -> {
            final char[] currentPass = new char[passBufferLength];
            System.arraycopy(passBuffer, 0, currentPass, 0, passBufferLength);

            //
            switch (status) {
                case checkStatus:
                    final String savedPassStr = EasylockSP.getString("password", null);
                    final char[] savedPass = savedPassStr == null ? null : savedPassStr.toCharArray();
                    if (currentPass.length > 0 && CryptoHelper.isEqual(currentPass, savedPass)) {
                        FileHelper.zero(currentPass);
                        FileHelper.zero(savedPass);
                        finish();
                    } else {
                        clearPassBuffer();
                        Toast.makeText(this, getString(R.string.incorrect_password_txt), Toast.LENGTH_SHORT).show();
                    }
                    FileHelper.zero(savedPass);
                    break;

                //
                case setStatus:
                    //
                    tempPass = currentPass;
                    clearPassBuffer();
                    status = setStatus1;

                    textViewHAHA.setText(R.string.confirm_password_txt);
                    return; // Do NOT zero currentPass, it's now stored in tempPass

                //
                case setStatus1:
                    //
                    if (CryptoHelper.isEqual(currentPass, tempPass)) {
                        EasylockSP.put("password", new String(currentPass));
                        Toast.makeText(LockscreenActivity.this, getString(R.string.password_is_set_txt), Toast.LENGTH_SHORT).show();
                        FileHelper.zero(tempPass);
                        tempPass = null;
                        gotoActivity();
                        FileHelper.zero(currentPass);
                        return;
                    } else {
                        FileHelper.zero(tempPass);
                        tempPass = null;
                        clearPassBuffer();
                        status = setStatus;

                        textViewHAHA.setText(R.string.enter_a_new_password_txt);
                        Toast.makeText(LockscreenActivity.this, getString(R.string.please_enter_a_new_password_again_txt), Toast.LENGTH_SHORT).show();
                    }
                    break;

                //
                case changeStatus:
                    final String savedPassStrChange = EasylockSP.getString("password", null);
                    final char[] savedPassChange = savedPassStrChange == null ? null : savedPassStrChange.toCharArray();
                    if (CryptoHelper.isEqual(currentPass, savedPassChange)) {
                        FileHelper.zero(tempPass);
                        tempPass = currentPass;
                        clearPassBuffer();
                        status = changeStatus1;

                        textViewHAHA.setText(R.string.enter_a_new_password_txt);
                        FileHelper.zero(savedPassChange);
                        return; // Do NOT zero currentPass, it's now stored in tempPass
                    } else {
                        clearPassBuffer();
                        Toast.makeText(LockscreenActivity.this, getString(R.string.please_enter_current_password_txt), Toast.LENGTH_SHORT).show();
                    }
                    FileHelper.zero(savedPassChange);
                    break;

                //
                case changeStatus1:
                    FileHelper.zero(tempPass);
                    tempPass = currentPass;
                    clearPassBuffer();
                    status = changeStatus2;

                    textViewHAHA.setText(R.string.confirm_password_txt);
                    return; // Do NOT zero currentPass, it's now stored in tempPass

                //
                case changeStatus2:
                    if (CryptoHelper.isEqual(currentPass, tempPass)) {
                        EasylockSP.put("password", new String(currentPass));
                        Toast.makeText(LockscreenActivity.this, getString(R.string.password_changed_txt), Toast.LENGTH_SHORT).show();
                        FileHelper.zero(tempPass);
                        tempPass = null;
                        gotoActivity();
                        FileHelper.zero(currentPass);
                        return;
                    } else {
                        FileHelper.zero(tempPass);
                        tempPass = null;
                        clearPassBuffer();
                        status = changeStatus1;

                        textViewHAHA.setText(R.string.enter_a_new_password_txt);
                        Toast.makeText(LockscreenActivity.this, getString(R.string.please_enter_a_new_password_again_txt), Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
            FileHelper.zero(currentPass);
        });

        for (int passButtonId : passButtonIds) {
            final Button button = findViewById(passButtonId);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (passBufferLength >= 8) {
                        Toast.makeText(LockscreenActivity.this, getString(R.string.max_8_characters_txt), Toast.LENGTH_SHORT).show();
                    } else {
                        passBuffer[passBufferLength] = button.getText().charAt(0);
                        passBufferLength++;
                    }
                    updateDotText();
                }
            });
        }
    }

    private void updateDotText() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < passBufferLength; i++) {
            builder.append("*");
        }
        textViewDot.setText(builder.toString());
    }

    private void clearPassBuffer() {
        FileHelper.zero(passBuffer);
        passBufferLength = 0;
        updateDotText();
    }

    private String getPassword() {
        return EasylockSP.getString("password", null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearPassBuffer();
        FileHelper.zero(tempPass);
    }

    private void gotoActivity() {
        Intent intent = new Intent(LockscreenActivity.this, activityClassToGo);
        startActivity(intent);
        finish();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void activityClass(Class activityClass) {
        activityClassToGo = activityClass;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (status.equals("check")) {
            finishAffinity();
        }
    }

}
