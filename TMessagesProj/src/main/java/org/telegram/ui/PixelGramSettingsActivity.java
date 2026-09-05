package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Range;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApiCredentials;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PixelGramUpdateChecker;
import org.telegram.messenger.R;
import org.telegram.messenger.camera.Camera2Session;
import org.telegram.messenger.camera.PixelGramSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Settings screen for PixelGram's camera/recording tuning (see PixelGramSettings for the
 * backing store). Options are gated against Camera2Session's cached hardware capability
 * checks - noise reduction, edge mode, and exposure compensation are read fresh on every
 * capture request rather than cached once, so changes here take effect on the next frame.
 */
public class PixelGramSettingsActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    // Computed once in buildRows() rather than on every bind/isEnabled() call - a real
    // CameraCharacteristics query (Camera2Session.queryLowLightBoostSupported), same reasoning
    // as PixelGramSettings' own mic-preference-probe caching.
    private boolean lowLightBoostAvailable;

    private int headerCredentialsRow;
    private int apiCredentialsRow;
    private int divider0Row;

    private int headerRecordingRow;
    private int resolutionRow;
    private int videoBitrateRow;
    private int audioBitrateRow;
    private int debugLoggingRow;
    private int divider1Row;

    private int headerQualityRow;
    private int noiseReductionRow;
    private int edgeModeRow;
    private int tonemapModeRow;
    private int faceAeMeteringRow;
    private int lowLightBoostRow;
    // TEMPORARY investigation scaffolding (see PixelGramSettings.KEY_LLB_TEST_FPS_RANGE) - only
    // added to the row list when BuildVars.DEBUG_VERSION; stays -1 (never matches a real
    // position) otherwise. Remove together with the setting once LLB's default is settled.
    private int llbTestFpsRangeRow = -1;
    private int exposureCompensationRow;
    private int downscaleFilterRow;
    private int ditherAmountRow;
    private int divider2Row;

    private int headerAudioRow;
    private int voiceEnhancementRow;
    private int noiseSuppressionRow;
    private int agcRow;
    private int echoCancellationRow;
    private int micGainRow;
    // Separate row/setting from micGainRow (round video) - see PixelGramSettings.
    // DEFAULT_MIC_GAIN_VOICE_MESSAGE's doc for why these aren't shared.
    private int micGainVoiceMessageRow;
    private int micDirectionRow;
    private int micFieldDimensionRow;
    private int voiceIsolationRow;
    private int gateThresholdRow;
    private int speechEnhancementRow;
    private int denoiserStrengthRow;
    // Voice messages only - see audio.c's initRecorder()/FINDINGS.md's Opus encoder
    // configuration section. Round video's audio path is AAC/MediaCodec, unrelated to these.
    private int opusApplicationRow;
    private int opusBitrateRow;
    private int divider3Row;

    private int resetRow;
    private int divider4Row;

    private int headerUpdatesRow;
    private int checkNowRow;
    private int updateInfoRow;

    private int rowCount;

    private static final int TYPE_SHADOW = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_HEADER = 2;
    private static final int TYPE_CHECK = 3;
    private static final int TYPE_INFO = 4;

    // RecyclerListView's click dispatch doesn't consult View.isEnabled(), so a capability-gated
    // row's own click handler is what actually has to no-op when unavailable (see the
    // noiseSuppressionRow/agcRow/echoCancellationRow branches in the item click listener below) -
    // isEnabled(holder) alone only dims the row's checkbox internals, not the row overall, and
    // doesn't block taps. This alpha is the actual visible "can't use this" affordance; the
    // "(unavailable)" text suffix stays regardless so the reason is legible even at full alpha.
    private static final float DISABLED_ROW_ALPHA = 0.5f;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        buildRows();
        return true;
    }

    private void buildRows() {
        rowCount = 0;
        headerCredentialsRow = rowCount++;
        apiCredentialsRow = rowCount++;
        divider0Row = rowCount++;

        headerRecordingRow = rowCount++;
        resolutionRow = rowCount++;
        videoBitrateRow = rowCount++;
        audioBitrateRow = rowCount++;
        debugLoggingRow = rowCount++;
        divider1Row = rowCount++;

        headerQualityRow = rowCount++;
        noiseReductionRow = rowCount++;
        edgeModeRow = rowCount++;
        tonemapModeRow = rowCount++;
        faceAeMeteringRow = rowCount++;
        lowLightBoostAvailable = Camera2Session.queryLowLightBoostSupported(true) && Camera2Session.queryLowLightBoostSupported(false);
        lowLightBoostRow = rowCount++;
        if (BuildVars.DEBUG_VERSION) {
            llbTestFpsRangeRow = rowCount++;
        }
        exposureCompensationRow = rowCount++;
        downscaleFilterRow = rowCount++;
        ditherAmountRow = rowCount++;
        divider2Row = rowCount++;

        headerAudioRow = rowCount++;
        voiceEnhancementRow = rowCount++;
        noiseSuppressionRow = rowCount++;
        agcRow = rowCount++;
        echoCancellationRow = rowCount++;
        micGainRow = rowCount++;
        micGainVoiceMessageRow = rowCount++;
        micDirectionRow = rowCount++;
        micFieldDimensionRow = rowCount++;
        voiceIsolationRow = rowCount++;
        gateThresholdRow = rowCount++;
        speechEnhancementRow = rowCount++;
        denoiserStrengthRow = rowCount++;
        opusApplicationRow = rowCount++;
        opusBitrateRow = rowCount++;
        divider3Row = rowCount++;

        resetRow = rowCount++;
        divider4Row = rowCount++;

        headerUpdatesRow = rowCount++;
        checkNowRow = rowCount++;
        updateInfoRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("PixelGram Camera");
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position, x, y) -> {
            if (position == apiCredentialsRow) {
                presentFragment(new ApiCredentialsSetupActivity().withPrefill(ApiCredentials.getApiId(), ApiCredentials.getApiHash()));
            } else if (position == resolutionRow) {
                showResolutionDialog();
            } else if (position == videoBitrateRow) {
                showVideoBitrateDialog();
            } else if (position == audioBitrateRow) {
                showAudioBitrateDialog();
            } else if (position == debugLoggingRow) {
                PixelGramSettings.setDebugLoggingEnabled(!PixelGramSettings.isDebugLoggingEnabled());
                ((TextCheckCell) view).setChecked(PixelGramSettings.isDebugLoggingEnabled());
            } else if (position == noiseReductionRow) {
                showNoiseReductionDialog();
            } else if (position == edgeModeRow) {
                showEdgeModeDialog();
            } else if (position == tonemapModeRow) {
                showTonemapModeDialog();
            } else if (position == faceAeMeteringRow) {
                PixelGramSettings.setFaceAeMeteringEnabled(!PixelGramSettings.isFaceAeMeteringEnabled());
                ((TextCheckCell) view).setChecked(PixelGramSettings.isFaceAeMeteringEnabled());
            } else if (position == lowLightBoostRow) {
                // Same click-bypass issue as the other capability-gated rows - isEnabled(holder)
                // only dims the row, it doesn't stop this listener from firing.
                if (lowLightBoostAvailable) {
                    PixelGramSettings.setLowLightBoostEnabled(!PixelGramSettings.isLowLightBoostEnabled());
                    ((TextCheckCell) view).setChecked(PixelGramSettings.isLowLightBoostEnabled());
                }
            } else if (position == llbTestFpsRangeRow) {
                showLlbTestFpsRangeDialog();
            } else if (position == exposureCompensationRow) {
                showExposureCompensationDialog();
            } else if (position == downscaleFilterRow) {
                showDownscaleFilterDialog();
            } else if (position == ditherAmountRow) {
                showDitherAmountDialog();
            } else if (position == voiceEnhancementRow) {
                showVoiceEnhancementDialog();
            } else if (position == noiseSuppressionRow) {
                // RecyclerListView's click dispatch (RecyclerListViewItemClickListener.onPressItem)
                // fires unconditionally - it never consults View.isEnabled() - so the adapter's
                // isEnabled(holder) only grays the row cosmetically and does not itself block
                // taps. Re-check isAvailable() here and no-op if unsupported, rather than relying
                // on isEnabled(holder) to have stopped this from firing at all.
                if (NoiseSuppressor.isAvailable()) {
                    PixelGramSettings.setNoiseSuppressionEnabled(!PixelGramSettings.isNoiseSuppressionEnabled());
                    ((TextCheckCell) view).setChecked(PixelGramSettings.isNoiseSuppressionEnabled());
                }
            } else if (position == agcRow) {
                if (AutomaticGainControl.isAvailable()) {
                    PixelGramSettings.setAgcEnabled(!PixelGramSettings.isAgcEnabled());
                    ((TextCheckCell) view).setChecked(PixelGramSettings.isAgcEnabled());
                }
            } else if (position == echoCancellationRow) {
                if (AcousticEchoCanceler.isAvailable()) {
                    PixelGramSettings.setEchoCancellationEnabled(!PixelGramSettings.isEchoCancellationEnabled());
                    ((TextCheckCell) view).setChecked(PixelGramSettings.isEchoCancellationEnabled());
                }
            } else if (position == micGainRow) {
                showMicGainDialog();
            } else if (position == micGainVoiceMessageRow) {
                showMicGainVoiceMessageDialog();
            } else if (position == micDirectionRow) {
                // Same click-bypass issue as the audio-effect check rows: isEnabled(holder)
                // only dims the row, it doesn't stop this listener from firing. Re-check here.
                if (PixelGramSettings.isMicDirectionSupported()) {
                    showMicDirectionDialog();
                }
            } else if (position == micFieldDimensionRow) {
                if (PixelGramSettings.isMicFieldDimensionSupported()) {
                    showMicFieldDimensionDialog();
                }
            } else if (position == voiceIsolationRow) {
                showVoiceIsolationDialog();
            } else if (position == gateThresholdRow) {
                showGateThresholdDialog();
            } else if (position == speechEnhancementRow) {
                showSpeechEnhancementDialog();
            } else if (position == denoiserStrengthRow) {
                showDenoiserStrengthDialog();
            } else if (position == opusApplicationRow) {
                showOpusApplicationDialog();
            } else if (position == opusBitrateRow) {
                showOpusBitrateDialog();
            } else if (position == resetRow) {
                showResetDialog();
            } else if (position == checkNowRow) {
                PixelGramUpdateChecker.checkForUpdates(true, () -> listAdapter.notifyItemChanged(updateInfoRow));
            }
        });

        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDurations(350);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);

        return fragmentView;
    }

    // --- Capability gating (settings screen has no open camera session) ---

    /** A mode is offered only if both front and rear support it - the setting has no way to
     * know which camera will be active when recording starts. */
    private static boolean[] supportedIntersection(int[] modes, int[] frontAvailable, int[] rearAvailable) {
        boolean[] result = new boolean[modes.length];
        for (int i = 0; i < modes.length; i++) {
            result[i] = contains(frontAvailable, modes[i]) && contains(rearAvailable, modes[i]);
        }
        return result;
    }

    private static boolean contains(int[] array, int value) {
        if (array == null) return false;
        for (int v : array) {
            if (v == value) return true;
        }
        return false;
    }

    /** [minEv, maxEv], the overlap of front/rear CONTROL_AE_COMPENSATION_RANGE * _STEP,
     * falling back to a generic +-2.0 EV if either camera's characteristics aren't available. */
    private static float[] exposureCompensationEvRange() {
        try {
            Range<Integer> rangeFront = Camera2Session.queryExposureCompensationRange(true);
            Rational stepFront = Camera2Session.queryExposureCompensationStep(true);
            Range<Integer> rangeRear = Camera2Session.queryExposureCompensationRange(false);
            Rational stepRear = Camera2Session.queryExposureCompensationStep(false);
            if (rangeFront != null && stepFront != null && stepFront.floatValue() != 0f
                    && rangeRear != null && stepRear != null && stepRear.floatValue() != 0f) {
                float frontMin = rangeFront.getLower() * stepFront.floatValue();
                float frontMax = rangeFront.getUpper() * stepFront.floatValue();
                float rearMin = rangeRear.getLower() * stepRear.floatValue();
                float rearMax = rangeRear.getUpper() * stepRear.floatValue();
                float minEv = Math.max(frontMin, rearMin);
                float maxEv = Math.min(frontMax, rearMax);
                if (minEv < maxEv) {
                    return new float[]{minEv, maxEv};
                }
            }
        } catch (Exception ignore) {
        }
        return new float[]{-2.0f, 2.0f};
    }

    // --- Dialogs ---

    // Ratio annotations are against the 1920px supersample capture size (see
    // Camera2Session.chooseSupersampleCaptureSize / FINDINGS.md) - only shown where it comes out
    // to a clean integer, since that's the point worth knowing (an exact-ratio downscale vs. one
    // that lands between two capture texels). 384 is annotated as stock Telegram's own round-video
    // resolution for reference, not because it's this app's default.
    private void showResolutionDialog() {
        // 656, 672, 704, 720, 800 and 960 confirmed or presumed rejected server-side (same
        // square-fallback failure, at upload time rather than in any client) - see FINDINGS.md's
        // "960px: a separate, real server-side ceiling". The ceiling is now known to sit in
        // 640-655 inclusive, with 640 itself the most likely value. 640 is both the maximum
        // offered and the default.
        CharSequence[] options = {"320 px (6:1)", "384 px (5:1, stock)", "448 px", "480 px (4:1)", "512 px", "640 px (3:1, max, default)"};
        int[] values = {320, 384, 448, 480, 512, 640};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Resolution");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setResolution(values[which]);
            listAdapter.notifyItemChanged(resolutionRow);
        });
        showDialog(builder.create());
    }

    // TEMPORARY investigation scaffolding (see PixelGramSettings.KEY_LLB_TEST_FPS_RANGE) - only
    // reachable when llbTestFpsRangeRow exists (BuildVars.DEBUG_VERSION). Remove together with the
    // setting once Low Light Boost's default is settled.
    private void showLlbTestFpsRangeDialog() {
        CharSequence[] options = {"Auto (default, today's behavior)", "[30,30]", "[24,30]", "[15,30]"};
        int[] values = {PixelGramSettings.LLB_TEST_FPS_AUTO, PixelGramSettings.LLB_TEST_FPS_30_30, PixelGramSettings.LLB_TEST_FPS_24_30, PixelGramSettings.LLB_TEST_FPS_15_30};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("LLB Test FPS Range (DEBUG)");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setLlbTestFpsRange(values[which]);
            listAdapter.notifyItemChanged(llbTestFpsRangeRow);
        });
        showDialog(builder.create());
    }

    private void showVideoBitrateDialog() {
        // Bitrates above ~1.2-1.3Mbps risk the round-video file-size ceiling over a full 60s
        // recording (see PixelGramSettings.ROUND_VIDEO_SAFE_MAX_BYTES) - PixelGramSettings
        // automatically caps whatever's picked here at recording time, so nothing above is
        // actually unsafe to select, just possibly reduced in practice for a long recording.
        CharSequence[] options = {"800 kbps", "1.0 Mbps (default)", "1.1 Mbps", "1.2 Mbps", "1.25 Mbps", "1.4 Mbps", "1.5 Mbps", "1.6 Mbps", "1.75 Mbps", "2 Mbps", "3 Mbps", "4 Mbps", "6 Mbps"};
        int[] values = {800_000, 1_000_000, 1_100_000, 1_200_000, 1_250_000, 1_400_000, 1_500_000, 1_600_000, 1_750_000, 2_000_000, 3_000_000, 4_000_000, 6_000_000};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Video Bitrate");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setVideoBitrate(values[which]);
            listAdapter.notifyItemChanged(videoBitrateRow);
        });
        showDialog(builder.create());
    }

    private void showAudioBitrateDialog() {
        CharSequence[] options = {"64 kbps", "96 kbps (default)", "128 kbps"};
        int[] values = {64_000, 96_000, 128_000};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Audio Bitrate");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setAudioBitrate(values[which]);
            listAdapter.notifyItemChanged(audioBitrateRow);
        });
        showDialog(builder.create());
    }

    private void showNoiseReductionDialog() {
        String[] names = {"Off (default)", "Fast", "High Quality"};
        int[] values = {PixelGramSettings.NOISE_REDUCTION_OFF, PixelGramSettings.NOISE_REDUCTION_FAST, PixelGramSettings.NOISE_REDUCTION_HIGH_QUALITY};
        boolean[] supported = supportedIntersection(values, Camera2Session.queryAvailableNoiseReductionModes(true), Camera2Session.queryAvailableNoiseReductionModes(false));
        showModeDialog("Noise Reduction", names, values, supported, noiseReductionRow, PixelGramSettings::setNoiseReductionMode);
    }

    private void showEdgeModeDialog() {
        String[] names = {"Off (default)", "Fast", "High Quality"};
        int[] values = {PixelGramSettings.EDGE_MODE_OFF, PixelGramSettings.EDGE_MODE_FAST, PixelGramSettings.EDGE_MODE_HIGH_QUALITY};
        boolean[] supported = supportedIntersection(values, Camera2Session.queryAvailableEdgeModes(true), Camera2Session.queryAvailableEdgeModes(false));
        showModeDialog("Edge Mode", names, values, supported, edgeModeRow, PixelGramSettings::setEdgeMode);
    }

    private void showTonemapModeDialog() {
        String[] names = {"Fast (default)", "High Quality"};
        int[] values = {PixelGramSettings.TONEMAP_MODE_FAST, PixelGramSettings.TONEMAP_MODE_HIGH_QUALITY};
        boolean[] supported = supportedIntersection(values, Camera2Session.queryAvailableTonemapModes(true), Camera2Session.queryAvailableTonemapModes(false));
        showModeDialog("Tone Mapping", names, values, supported, tonemapModeRow, PixelGramSettings::setTonemapMode);
    }

    private void showVoiceEnhancementDialog() {
        String[] names = {"Off (raw mic, default)", "Voice communication", "Voice recognition", "Camcorder", "Unprocessed"};
        int[] values = {
                PixelGramSettings.VOICE_ENHANCEMENT_OFF,
                PixelGramSettings.VOICE_ENHANCEMENT_VOICE_COMMUNICATION,
                PixelGramSettings.VOICE_ENHANCEMENT_VOICE_RECOGNITION,
                PixelGramSettings.VOICE_ENHANCEMENT_CAMCORDER,
                PixelGramSettings.VOICE_ENHANCEMENT_UNPROCESSED
        };
        // AudioSource support generally isn't queryable ahead of time the way
        // CameraCharacteristics modes are, except UNPROCESSED, which has its own dedicated
        // AudioManager property - see PixelGramSettings.isUnprocessedAudioSourceSupported().
        boolean[] supported = {true, true, true, true, PixelGramSettings.isUnprocessedAudioSourceSupported()};
        showModeDialog("Voice Enhancement", names, values, supported, voiceEnhancementRow, PixelGramSettings::setVoiceEnhancementMode);
    }

    private void showMicGainDialog() {
        String[] names = {"1x (off, default)", "1.5x", "2x", "3x", "4x", "5x"};
        int[] values = {
                PixelGramSettings.MIC_GAIN_1X,
                PixelGramSettings.MIC_GAIN_1_5X,
                PixelGramSettings.MIC_GAIN_2X,
                PixelGramSettings.MIC_GAIN_3X,
                PixelGramSettings.MIC_GAIN_4X,
                PixelGramSettings.MIC_GAIN_5X
        };
        boolean[] supported = {true, true, true, true, true, true};
        showModeDialog("Microphone Gain (Round Video)", names, values, supported, micGainRow, PixelGramSettings::setMicGainMode);
    }

    /** Separate gain setting from round video's own (see PixelGramSettings.
     * DEFAULT_MIC_GAIN_VOICE_MESSAGE's doc for why) - same underlying gain+limiter code, just a
     * different default and a different stored preference. */
    private void showMicGainVoiceMessageDialog() {
        String[] names = {"1x (off)", "1.5x", "2x", "3x (default)", "4x", "5x"};
        int[] values = {
                PixelGramSettings.MIC_GAIN_1X,
                PixelGramSettings.MIC_GAIN_1_5X,
                PixelGramSettings.MIC_GAIN_2X,
                PixelGramSettings.MIC_GAIN_3X,
                PixelGramSettings.MIC_GAIN_4X,
                PixelGramSettings.MIC_GAIN_5X
        };
        boolean[] supported = {true, true, true, true, true, true};
        showModeDialog("Microphone Gain (Voice Messages)", names, values, supported, micGainVoiceMessageRow, PixelGramSettings::setMicGainModeVoiceMessage);
    }

    private void showMicDirectionDialog() {
        String[] names = {"Off (default)", "Towards user", "Away from user", "Auto (follows camera)"};
        int[] values = {
                PixelGramSettings.MIC_DIRECTION_OFF,
                PixelGramSettings.MIC_DIRECTION_TOWARDS_USER,
                PixelGramSettings.MIC_DIRECTION_AWAY_FROM_USER,
                PixelGramSettings.MIC_DIRECTION_AUTO
        };
        boolean[] supported = {true, true, true, true};
        showModeDialog("Microphone Direction", names, values, supported, micDirectionRow, PixelGramSettings::setMicDirectionMode);
    }

    /** Field dimension is a float, not one of showModeDialog's int values, so this is a plain
     * setItems dialog rather than a reuse of that helper - whole-row availability (not
     * per-option) is already handled by the click listener's isMicFieldDimensionSupported()
     * guard before this is ever shown. */
    private void showMicFieldDimensionDialog() {
        float[] values = PixelGramSettings.MIC_FIELD_DIMENSION_VALUES;
        CharSequence[] options = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            options[i] = formatMicFieldDimension(values[i]) + (values[i] == PixelGramSettings.DEFAULT_MIC_FIELD_DIMENSION ? " (default)" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Microphone Field Dimension");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setMicFieldDimension(values[which]);
            listAdapter.notifyItemChanged(micFieldDimensionRow);
        });
        showDialog(builder.create());
    }

    private void showVoiceIsolationDialog() {
        String[] names = {"Off", "Bandpass", "Bandpass + Gate (default)"};
        int[] values = {
                PixelGramSettings.VOICE_ISOLATION_OFF,
                PixelGramSettings.VOICE_ISOLATION_BANDPASS,
                PixelGramSettings.VOICE_ISOLATION_BANDPASS_GATE
        };
        boolean[] supported = {true, true, true};
        showModeDialog("Voice Isolation", names, values, supported, voiceIsolationRow, PixelGramSettings::setVoiceIsolationMode);
    }

    /** RNN-based denoiser, applied before Voice Isolation/gain - see SpeechEnhancer's class doc
     * and FINDINGS.md for the three-way comparison this setting exists to run. Only DeepFilterNet
     * was found to have a GPL-incompatibility problem (Apache-2.0 wrapper vs. this app's GPLv2) -
     * see FINDINGS.md - so it isn't offered here at all, not even disabled. */
    private void showSpeechEnhancementDialog() {
        String[] names = {"Off (default)", "RNNoise"};
        int[] values = {
                PixelGramSettings.SPEECH_ENHANCEMENT_OFF,
                PixelGramSettings.SPEECH_ENHANCEMENT_RNNOISE
        };
        boolean[] supported = {true, true};
        showModeDialog("Speech Enhancement", names, values, supported, speechEnhancementRow, PixelGramSettings::setSpeechEnhancementMode);
    }

    /** Wet/dry blend after RNNoise - see SpeechEnhancer's class doc. Float values, same reason as
     * showGateThresholdDialog for a plain setItems dialog rather than showModeDialog. */
    private void showDenoiserStrengthDialog() {
        float[] values = PixelGramSettings.SPEECH_ENHANCEMENT_WET_VALUES;
        CharSequence[] options = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            options[i] = formatDenoiserStrength(values[i]) + (values[i] == PixelGramSettings.DEFAULT_SPEECH_ENHANCEMENT_WET ? " (default)" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Denoiser Strength");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setSpeechEnhancementWetFraction(values[which]);
            listAdapter.notifyItemChanged(denoiserStrengthRow);
        });
        showDialog(builder.create());
    }

    /** Voice-message Opus encoder application mode - see audio.c/FINDINGS.md's Opus encoder
     * configuration section. */
    private void showOpusApplicationDialog() {
        String[] names = {"Audio (default)", "VOIP"};
        int[] values = {
                PixelGramSettings.OPUS_APPLICATION_AUDIO,
                PixelGramSettings.OPUS_APPLICATION_VOIP
        };
        boolean[] supported = {true, true};
        showModeDialog("Opus Application Mode", names, values, supported, opusApplicationRow, PixelGramSettings::setOpusApplicationMode);
    }

    /** Voice-message Opus encoder VBR bitrate target - see audio.c/FINDINGS.md's Opus encoder
     * configuration section. Int values, same reason as showDenoiserStrengthDialog for a plain
     * setItems dialog rather than showModeDialog. */
    private void showOpusBitrateDialog() {
        int[] values = PixelGramSettings.OPUS_BITRATE_VALUES;
        CharSequence[] options = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            options[i] = formatOpusBitrate(values[i]) + (values[i] == PixelGramSettings.DEFAULT_OPUS_BITRATE ? " (default)" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Opus Bitrate");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setOpusBitrate(values[which]);
            listAdapter.notifyItemChanged(opusBitrateRow);
        });
        showDialog(builder.create());
    }

    /** Threshold is a float, not one of showModeDialog's int values, same reason as
     * showMicFieldDimensionDialog - a plain setItems dialog rather than a reuse of that helper. */
    private void showGateThresholdDialog() {
        float[] values = PixelGramSettings.GATE_THRESHOLD_DB_VALUES;
        CharSequence[] options = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            options[i] = formatGateThreshold(values[i]) + (values[i] == PixelGramSettings.DEFAULT_GATE_THRESHOLD_DB ? " (default)" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Gate Threshold");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setVoiceIsolationGateThresholdDb(values[which]);
            listAdapter.notifyItemChanged(gateThresholdRow);
        });
        showDialog(builder.create());
    }

    private interface IntSetter {
        void set(int value);
    }

    /** Unsupported entries are dimmed and suffixed "(unsupported)"; tapping one is a no-op -
     * AlertDialog.setItems has no native per-item disable. */
    private void showModeDialog(String title, String[] names, int[] values, boolean[] supported, int row, IntSetter setter) {
        CharSequence[] options = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) {
            if (supported[i]) {
                options[i] = names[i];
            } else {
                SpannableString s = new SpannableString(names[i] + " (unsupported)");
                s.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2)), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                options[i] = s;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(options, (dialog, which) -> {
            if (!supported[which]) return;
            setter.set(values[which]);
            listAdapter.notifyItemChanged(row);
        });
        showDialog(builder.create());
    }

    private void showExposureCompensationDialog() {
        float[] evRange = exposureCompensationEvRange();
        float minEv = evRange[0], maxEv = evRange[1];
        float step = 0.1f;
        int maxProgress = Math.max(1, Math.round((maxEv - minEv) / step));
        int currentProgress = Math.round((clampFloat(PixelGramSettings.getExposureCompensationEv(), minEv, maxEv) - minEv) / step);

        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        layout.setPadding(pad, AndroidUtilities.dp(8), pad, 0);

        TextView valueLabel = new TextView(getParentActivity());
        valueLabel.setText(formatEv(minEv + currentProgress * step));
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        valueLabel.setGravity(Gravity.CENTER);
        valueLabel.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        layout.addView(valueLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        SeekBar seekBar = new SeekBar(getParentActivity());
        seekBar.setMax(maxProgress);
        seekBar.setProgress(currentProgress);
        layout.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        float[] selected = {minEv + currentProgress * step};
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                selected[0] = minEv + progress * step;
                valueLabel.setText(formatEv(selected[0]));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Exposure Compensation");
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            PixelGramSettings.setExposureCompensationEv(selected[0]);
            listAdapter.notifyItemChanged(exposureCompensationRow);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showDownscaleFilterDialog() {
        String[] names = {"Lanczos (default, sharpest)", "Box (softest, most compressible)", "Gaussian"};
        int[] values = {
                PixelGramSettings.DOWNSCALE_FILTER_LANCZOS,
                PixelGramSettings.DOWNSCALE_FILTER_BOX,
                PixelGramSettings.DOWNSCALE_FILTER_GAUSSIAN
        };
        boolean[] supported = {true, true, true};
        showModeDialog("Downscale Filter", names, values, supported, downscaleFilterRow, PixelGramSettings::setDownscaleFilter);
    }

    /** LSB amount is a float, not one of showModeDialog's int values, same reason as
     * showGateThresholdDialog - a plain setItems dialog rather than a reuse of that helper. */
    private void showDitherAmountDialog() {
        float[] values = PixelGramSettings.DITHER_AMOUNT_LSB_VALUES;
        CharSequence[] options = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            options[i] = formatDitherAmount(values[i]) + (values[i] == PixelGramSettings.DEFAULT_DITHER_AMOUNT_LSB ? " (default)" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Dither Amount");
        builder.setItems(options, (dialog, which) -> {
            PixelGramSettings.setDitherAmountLsb(values[which]);
            listAdapter.notifyItemChanged(ditherAmountRow);
        });
        showDialog(builder.create());
    }

    private void showResetDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Reset to Defaults");
        builder.setMessage("Restore all PixelGram camera settings to their default values?");
        builder.setPositiveButton("Reset", (dialog, which) -> {
            PixelGramSettings.resetToDefaults();
            listAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    // --- Formatters ---

    private static String formatEv(float ev) {
        return String.format(Locale.US, "%+.1f EV", ev);
    }

    private static String currentVersionName() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String formatLastCheck(long ms) {
        if (ms <= 0) {
            return "never";
        }
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(new Date(ms));
    }

    private static String formatBitrate(int bps) {
        return String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f);
    }

    private static String modeName(int mode, int off, int fast, int hq) {
        if (mode == off) return "Off";
        if (mode == fast) return "Fast";
        if (mode == hq) return "High Quality";
        return String.valueOf(mode);
    }

    private static String tonemapModeName(int mode) {
        return mode == PixelGramSettings.TONEMAP_MODE_HIGH_QUALITY ? "High Quality" : "Fast";
    }

    // TEMPORARY investigation scaffolding - see PixelGramSettings.KEY_LLB_TEST_FPS_RANGE.
    private static String llbTestFpsRangeName(int mode) {
        switch (mode) {
            case PixelGramSettings.LLB_TEST_FPS_30_30: return "[30,30]";
            case PixelGramSettings.LLB_TEST_FPS_24_30: return "[24,30]";
            case PixelGramSettings.LLB_TEST_FPS_15_30: return "[15,30]";
            default: return "Auto (default)";
        }
    }

    private static String formatMicGain(int mode) {
        switch (mode) {
            case PixelGramSettings.MIC_GAIN_1_5X: return "1.5x";
            case PixelGramSettings.MIC_GAIN_2X: return "2x";
            case PixelGramSettings.MIC_GAIN_3X: return "3x";
            case PixelGramSettings.MIC_GAIN_4X: return "4x";
            case PixelGramSettings.MIC_GAIN_5X: return "5x";
            default: return "1x (off)";
        }
    }

    private static String formatMicDirection(int mode) {
        switch (mode) {
            case PixelGramSettings.MIC_DIRECTION_TOWARDS_USER: return "Towards user";
            case PixelGramSettings.MIC_DIRECTION_AWAY_FROM_USER: return "Away from user";
            case PixelGramSettings.MIC_DIRECTION_OFF: return "Off";
            case PixelGramSettings.MIC_DIRECTION_AUTO:
            default: return "Auto";
        }
    }

    private static String formatMicFieldDimension(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String voiceIsolationName(int mode) {
        switch (mode) {
            case PixelGramSettings.VOICE_ISOLATION_BANDPASS: return "Bandpass";
            case PixelGramSettings.VOICE_ISOLATION_BANDPASS_GATE: return "Bandpass + Gate";
            default: return "Off";
        }
    }

    private static String formatGateThreshold(float db) {
        return String.format(Locale.US, "%.0f dB", db);
    }

    private static String speechEnhancementName(int mode) {
        switch (mode) {
            case PixelGramSettings.SPEECH_ENHANCEMENT_RNNOISE: return "RNNoise";
            default: return "Off";
        }
    }

    private static String formatDenoiserStrength(float wet) {
        return Math.round(wet * 100) + "% wet";
    }

    private static String opusApplicationName(int mode) {
        return mode == PixelGramSettings.OPUS_APPLICATION_VOIP ? "VOIP" : "Audio";
    }

    private static String formatOpusBitrate(int bps) {
        return (bps / 1000) + " kbps";
    }


    private static String downscaleFilterName(int filter) {
        switch (filter) {
            case PixelGramSettings.DOWNSCALE_FILTER_BOX: return "Box";
            case PixelGramSettings.DOWNSCALE_FILTER_GAUSSIAN: return "Gaussian";
            default: return "Lanczos";
        }
    }

    private static String formatDitherAmount(float lsb) {
        if (lsb == 0f) return "Off";
        return String.format(Locale.US, "%.1fx LSB", lsb);
    }

    private static String voiceEnhancementName(int mode) {
        switch (mode) {
            case PixelGramSettings.VOICE_ENHANCEMENT_VOICE_COMMUNICATION: return "Voice communication";
            case PixelGramSettings.VOICE_ENHANCEMENT_VOICE_RECOGNITION: return "Voice recognition";
            case PixelGramSettings.VOICE_ENHANCEMENT_CAMCORDER: return "Camcorder";
            case PixelGramSettings.VOICE_ENHANCEMENT_UNPROCESSED: return "Unprocessed";
            default: return "Off";
        }
    }

    // --- Adapter ---

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            return pos == apiCredentialsRow
                    || pos == resolutionRow || pos == videoBitrateRow || pos == audioBitrateRow
                    || pos == debugLoggingRow
                    || pos == noiseReductionRow || pos == edgeModeRow || pos == tonemapModeRow || pos == faceAeMeteringRow || pos == exposureCompensationRow
                    || (pos == lowLightBoostRow && lowLightBoostAvailable) || pos == llbTestFpsRangeRow
                    || pos == downscaleFilterRow || pos == ditherAmountRow
                    || pos == voiceEnhancementRow
                    || (pos == noiseSuppressionRow && NoiseSuppressor.isAvailable())
                    || (pos == agcRow && AutomaticGainControl.isAvailable())
                    || (pos == echoCancellationRow && AcousticEchoCanceler.isAvailable())
                    || pos == micGainRow || pos == micGainVoiceMessageRow
                    || (pos == micDirectionRow && PixelGramSettings.isMicDirectionSupported())
                    || (pos == micFieldDimensionRow && PixelGramSettings.isMicFieldDimensionSupported())
                    || pos == voiceIsolationRow || pos == gateThresholdRow
                    || pos == speechEnhancementRow || pos == denoiserStrengthRow
                    || pos == opusApplicationRow || pos == opusBitrateRow
                    || pos == resetRow || pos == checkNowRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_SETTINGS:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(mContext, 22);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case TYPE_SHADOW:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == headerCredentialsRow) {
                        cell.setText("API Credentials");
                    } else if (position == headerRecordingRow) {
                        cell.setText("Recording");
                    } else if (position == headerQualityRow) {
                        cell.setText("Image Quality");
                    } else if (position == headerAudioRow) {
                        cell.setText("Audio");
                    } else if (position == headerUpdatesRow) {
                        cell.setText("Updates");
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setCanDisable(false);
                    // Reset before the per-row branches below - TYPE_SETTINGS views are recycled
                    // across all settings rows, so a view last bound to a dimmed capability-gated
                    // row (micDirectionRow/micFieldDimensionRow) must not stay dimmed when rebound
                    // to an always-available one.
                    cell.setAlpha(1f);
                    if (position == apiCredentialsRow) {
                        int apiId = ApiCredentials.getApiId();
                        cell.setTextAndValue("Change Credentials", apiId != 0 ? ("api_id " + apiId) : "not set", false);
                    } else if (position == resolutionRow) {
                        cell.setTextAndValue("Resolution", PixelGramSettings.getResolution() + " px", true);
                    } else if (position == videoBitrateRow) {
                        cell.setTextAndValue("Video Bitrate", formatBitrate(PixelGramSettings.getVideoBitrate()), true);
                    } else if (position == audioBitrateRow) {
                        cell.setTextAndValue("Audio Bitrate", (PixelGramSettings.getAudioBitrate() / 1000) + " kbps", true);
                    } else if (position == noiseReductionRow) {
                        cell.setTextAndValue("Noise Reduction", modeName(PixelGramSettings.getNoiseReductionMode(), PixelGramSettings.NOISE_REDUCTION_OFF, PixelGramSettings.NOISE_REDUCTION_FAST, PixelGramSettings.NOISE_REDUCTION_HIGH_QUALITY), true);
                    } else if (position == edgeModeRow) {
                        cell.setTextAndValue("Edge Mode", modeName(PixelGramSettings.getEdgeMode(), PixelGramSettings.EDGE_MODE_OFF, PixelGramSettings.EDGE_MODE_FAST, PixelGramSettings.EDGE_MODE_HIGH_QUALITY), true);
                    } else if (position == tonemapModeRow) {
                        cell.setTextAndValue("Tone Mapping", tonemapModeName(PixelGramSettings.getTonemapMode()), true);
                    } else if (position == llbTestFpsRangeRow) {
                        cell.setTextAndValue("LLB Test FPS Range (DEBUG)", llbTestFpsRangeName(PixelGramSettings.getLlbTestFpsRange()), true);
                    } else if (position == exposureCompensationRow) {
                        cell.setTextAndValue("Exposure Compensation", formatEv(PixelGramSettings.getExposureCompensationEv()), false);
                    } else if (position == downscaleFilterRow) {
                        cell.setTextAndValue("Downscale Filter", downscaleFilterName(PixelGramSettings.getDownscaleFilter()), true);
                    } else if (position == ditherAmountRow) {
                        cell.setTextAndValue("Dither Amount", formatDitherAmount(PixelGramSettings.getDitherAmountLsb()), false);
                    } else if (position == voiceEnhancementRow) {
                        cell.setTextAndValue("Voice Enhancement", voiceEnhancementName(PixelGramSettings.getVoiceEnhancementMode()), true);
                    } else if (position == micGainRow) {
                        cell.setTextAndValue("Microphone Gain (Round Video)", formatMicGain(PixelGramSettings.getMicGainMode()), true);
                    } else if (position == micGainVoiceMessageRow) {
                        cell.setTextAndValue("Microphone Gain (Voice Messages)", formatMicGain(PixelGramSettings.getMicGainModeVoiceMessage()), true);
                    } else if (position == micDirectionRow) {
                        boolean supported = PixelGramSettings.isMicDirectionSupported();
                        cell.setTextAndValue("Microphone Direction" + (supported ? "" : " (unavailable)"), formatMicDirection(PixelGramSettings.getMicDirectionMode()), true);
                        cell.setAlpha(supported ? 1f : DISABLED_ROW_ALPHA);
                    } else if (position == micFieldDimensionRow) {
                        boolean supported = PixelGramSettings.isMicFieldDimensionSupported();
                        cell.setTextAndValue("Microphone Field Dimension" + (supported ? "" : " (unavailable)"), formatMicFieldDimension(PixelGramSettings.getMicFieldDimension()), true);
                        cell.setAlpha(supported ? 1f : DISABLED_ROW_ALPHA);
                    } else if (position == voiceIsolationRow) {
                        cell.setTextAndValue("Voice Isolation", voiceIsolationName(PixelGramSettings.getVoiceIsolationMode()), true);
                    } else if (position == gateThresholdRow) {
                        cell.setTextAndValue("Gate Threshold", formatGateThreshold(PixelGramSettings.getVoiceIsolationGateThresholdDb()), false);
                    } else if (position == speechEnhancementRow) {
                        cell.setTextAndValue("Speech Enhancement", speechEnhancementName(PixelGramSettings.getSpeechEnhancementMode()), true);
                    } else if (position == denoiserStrengthRow) {
                        cell.setTextAndValue("Denoiser Strength", formatDenoiserStrength(PixelGramSettings.getSpeechEnhancementWetFraction()), true);
                    } else if (position == opusApplicationRow) {
                        cell.setTextAndValue("Opus Application Mode", opusApplicationName(PixelGramSettings.getOpusApplicationMode()), true);
                    } else if (position == opusBitrateRow) {
                        cell.setTextAndValue("Opus Bitrate", formatOpusBitrate(PixelGramSettings.getOpusBitrate()), false);
                    } else if (position == resetRow) {
                        cell.setCanDisable(true);
                        cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                        cell.setText("Reset to Defaults", false);
                    } else if (position == checkNowRow) {
                        cell.setText("Check Now", false);
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    // Reset before the per-row branches below - TYPE_CHECK views are recycled
                    // across all check rows, so a view last bound to a dimmed unavailable row
                    // must not stay dimmed when rebound to an always-available one.
                    cell.setAlpha(1f);
                    if (position == debugLoggingRow) {
                        cell.setTextAndCheck("Debug Logging", PixelGramSettings.isDebugLoggingEnabled(), false);
                    } else if (position == faceAeMeteringRow) {
                        cell.setTextAndCheck("Face-Weighted AE Metering", PixelGramSettings.isFaceAeMeteringEnabled(), true);
                    } else if (position == lowLightBoostRow) {
                        cell.setTextAndCheck("Low Light Boost" + (lowLightBoostAvailable ? "" : " (unavailable)"), PixelGramSettings.isLowLightBoostEnabled(), true);
                        cell.setAlpha(lowLightBoostAvailable ? 1f : DISABLED_ROW_ALPHA);
                    } else if (position == noiseSuppressionRow) {
                        boolean available = NoiseSuppressor.isAvailable();
                        cell.setTextAndCheck("Noise Suppression" + (available ? "" : " (unavailable)"), PixelGramSettings.isNoiseSuppressionEnabled(), true);
                        cell.setAlpha(available ? 1f : DISABLED_ROW_ALPHA);
                    } else if (position == agcRow) {
                        boolean available = AutomaticGainControl.isAvailable();
                        cell.setTextAndCheck("Automatic Gain Control" + (available ? "" : " (unavailable)"), PixelGramSettings.isAgcEnabled(), true);
                        cell.setAlpha(available ? 1f : DISABLED_ROW_ALPHA);
                    } else if (position == echoCancellationRow) {
                        boolean available = AcousticEchoCanceler.isAvailable();
                        cell.setTextAndCheck("Echo Cancellation" + (available ? "" : " (unavailable)"), PixelGramSettings.isEchoCancellationEnabled(), true);
                        cell.setAlpha(available ? 1f : DISABLED_ROW_ALPHA);
                    }
                    break;
                }
                case TYPE_INFO: {
                    if (position == updateInfoRow) {
                        TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                        cell.setText("PixelGram version: " + BuildVars.PIXELGRAM_VERSION
                                + "\nBased on Telegram: " + currentVersionName()
                                + "\nLast checked: " + formatLastCheck(PixelGramSettings.getLastUpdateCheckMs()));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == divider0Row || position == divider1Row || position == divider2Row || position == divider3Row || position == divider4Row) {
                return TYPE_SHADOW;
            } else if (position == headerCredentialsRow || position == headerRecordingRow || position == headerQualityRow || position == headerAudioRow || position == headerUpdatesRow) {
                return TYPE_HEADER;
            } else if (position == debugLoggingRow || position == faceAeMeteringRow || position == lowLightBoostRow
                    || position == noiseSuppressionRow || position == agcRow || position == echoCancellationRow) {
                return TYPE_CHECK;
            } else if (position == updateInfoRow) {
                return TYPE_INFO;
            } else {
                return TYPE_SETTINGS;
            }
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        listView.setPadding(0, 0, 0, bottom);
    }
}
