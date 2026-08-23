package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApiCredentials;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.regex.Pattern;

/**
 * Shown instead of login when no api_id/api_hash is stored yet (see
 * LaunchActivity.getClientNotActivatedFragment()) - every public Telegram-compatible client
 * needs its own pair from https://my.telegram.org, this fork doesn't ship one baked in.
 *
 * Also reused from PixelGramSettingsActivity's "API Credentials" row to change the pair later.
 */
public class ApiCredentialsSetupActivity extends BaseFragment {

    private static final Pattern API_HASH_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    private EditTextBoldCursor apiIdField;
    private EditTextBoldCursor apiHashField;
    private TextView errorView;
    private TextView continueButton;

    private int prefillApiId;
    private String prefillApiHash;

    /** Set before presenting this fragment (e.g. from PixelGramSettingsActivity's "change
     * credentials" row) - applied once createView() actually builds the fields, since
     * presentFragment() doesn't guarantee createView() has already run when this is called. */
    public ApiCredentialsSetupActivity withPrefill(int apiId, String apiHash) {
        prefillApiId = apiId;
        prefillApiHash = apiHash;
        return this;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(true);
        actionBar.setTitle("API Credentials");
        actionBar.setCastShadows(false);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(24);
        layout.setPadding(pad, AndroidUtilities.dp(16), pad, pad);
        scrollView.addView(layout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        TextView explanation = new TextView(context);
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        explanation.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        explanation.setLineSpacing(AndroidUtilities.dp(2), 1f);
        explanation.setText(AndroidUtilities.replaceSingleTag(
                "PixelGram needs its own Telegram API credentials to connect. Get a free api_id and api_hash from **my.telegram.org**, under \"API development tools\", then enter them below.",
                Theme.key_windowBackgroundWhiteLinkText, 0,
                () -> Browser.openUrl(getParentActivity(), "https://my.telegram.org")));
        layout.addView(explanation, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView apiIdLabel = sectionLabel(context, "api_id");
        layout.addView(apiIdLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        apiIdField = new EditTextBoldCursor(context);
        apiIdField.setInputType(InputType.TYPE_CLASS_NUMBER);
        styleField(context, apiIdField);
        layout.addView(apiIdField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 6, 0, 0));

        TextView apiHashLabel = sectionLabel(context, "api_hash");
        layout.addView(apiHashLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        apiHashField = new EditTextBoldCursor(context);
        apiHashField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        styleField(context, apiHashField);
        layout.addView(apiHashField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 6, 0, 0));

        errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        errorView.setVisibility(View.GONE);
        layout.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        continueButton = new TextView(context);
        continueButton.setText("Continue");
        continueButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        continueButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        continueButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        continueButton.setGravity(Gravity.CENTER);
        continueButton.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        continueButton.setOnClickListener(v -> onContinue());
        layout.addView(continueButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 0, 0));

        fragmentView = new FrameLayout(context);
        ((FrameLayout) fragmentView).addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        prefill(prefillApiId, prefillApiHash);
        return fragmentView;
    }

    private TextView sectionLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        return label;
    }

    private void styleField(Context context, EditTextBoldCursor field) {
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorWidth(1.5f);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
    }

    private void prefill(int apiId, String apiHash) {
        if (apiIdField != null && apiId != 0) {
            apiIdField.setText(String.valueOf(apiId));
        }
        if (apiHashField != null && !TextUtils.isEmpty(apiHash)) {
            apiHashField.setText(apiHash);
        }
    }

    private void onContinue() {
        String apiIdText = apiIdField.getText().toString().trim();
        String apiHashText = apiHashField.getText().toString().trim().toLowerCase();

        int apiId;
        try {
            apiId = Integer.parseInt(apiIdText);
        } catch (Exception e) {
            apiId = 0;
        }
        if (apiId <= 0) {
            showError("Enter a valid api_id (a positive number).");
            return;
        }
        if (!API_HASH_PATTERN.matcher(apiHashText).matches()) {
            showError("api_hash should be exactly 32 hex characters.");
            return;
        }

        errorView.setVisibility(View.GONE);
        ApiCredentials.setCredentials(apiId, apiHashText);
        restartApp();
    }

    private void showError(String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void restartApp() {
        Context context = ApplicationLoader.applicationContext;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        }
        Runtime.getRuntime().exit(0);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        // No account is usable yet without credentials - nothing sensible to go back to.
        return false;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }
}
