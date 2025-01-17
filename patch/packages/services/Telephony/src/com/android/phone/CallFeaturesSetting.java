/*
 * Copyright (c) 2012, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2008 The Android Open Source Project
 * Blacklist - Copyright (C) 2013 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.sip.SipManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ListAdapter;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.internal.telephony.util.BlacklistUtils;
import com.android.phone.PhoneUtils.PhoneSettings;
import com.android.phone.sip.SipSharedPreferences;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.android.internal.telephony.MSimConstants.SUB1;

/**
 * Top level "Call settings" UI; see res/xml/call_feature_setting.xml
 *
 * This preference screen is the root of the "Call settings" hierarchy
 * available from the Phone app; the settings here let you control various
 * features related to phone calls (including voicemail settings, SIP
 * settings, the "Respond via SMS" feature, and others.)  It's used only
 * on voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "Mobile network settings" screen under the main Settings app,
 * See {@link MobileNetworkSettings}.
 *
 * @see com.android.phone.MobileNetworkSettings
 */
public class CallFeaturesSetting extends PreferenceActivity
        implements DialogInterface.OnClickListener,
        Preference.OnPreferenceChangeListener,
        EditPhoneNumberPreference.OnDialogClosedListener,
        EditPhoneNumberPreference.GetDefaultNumberListener{
    private static final String LOG_TAG = "CallFeaturesSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    /**
     * Intent action to bring up Voicemail Provider settings.
     *
     * @see #IGNORE_PROVIDER_EXTRA
     */
    public static final String ACTION_ADD_VOICEMAIL =
            "com.android.phone.CallFeaturesSetting.ADD_VOICEMAIL";
    // intent action sent by this activity to a voice mail provider
    // to trigger its configuration UI
    public static final String ACTION_CONFIGURE_VOICEMAIL =
            "com.android.phone.CallFeaturesSetting.CONFIGURE_VOICEMAIL";
    // Extra put in the return from VM provider config containing voicemail number to set
    public static final String VM_NUMBER_EXTRA = "com.android.phone.VoicemailNumber";
    // Extra put in the return from VM provider config containing call forwarding number to set
    public static final String FWD_NUMBER_EXTRA = "com.android.phone.ForwardingNumber";
    // Extra put in the return from VM provider config containing call forwarding number to set
    public static final String FWD_NUMBER_TIME_EXTRA = "com.android.phone.ForwardingNumberTime";
    // If the VM provider returns non null value in this extra we will force the user to
    // choose another VM provider
    public static final String SIGNOUT_EXTRA = "com.android.phone.Signout";
    //Information about logical "up" Activity
    private static final String UP_ACTIVITY_PACKAGE = "com.android.dialer";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.dialer.DialtactsActivity";

    // Used to tell the saving logic to leave forwarding number as is
    public static final CallForwardInfo[] FWD_SETTINGS_DONT_TOUCH = null;
    // Suffix appended to provider key for storing vm number
    public static final String VM_NUMBER_TAG = "#VMNumber";
    // Suffix appended to provider key for storing forwarding settings
    public static final String FWD_SETTINGS_TAG = "#FWDSettings";
    // Suffix appended to forward settings key for storing length of settings array
    public static final String FWD_SETTINGS_LENGTH_TAG = "#Length";
    // Suffix appended to forward settings key for storing an individual setting
    public static final String FWD_SETTING_TAG = "#Setting";
    // Suffixes appended to forward setting key for storing an individual setting properties
    public static final String FWD_SETTING_STATUS = "#Status";
    public static final String FWD_SETTING_REASON = "#Reason";
    public static final String FWD_SETTING_NUMBER = "#Number";
    public static final String FWD_SETTING_TIME = "#Time";

    // Key identifying the default vocie mail provider
    public static final String DEFAULT_VM_PROVIDER_KEY = "";

    /**
     * String Extra put into ACTION_ADD_VOICEMAIL call to indicate which provider should be hidden
     * in the list of providers presented to the user. This allows a provider which is being
     * disabled (e.g. GV user logging out) to force the user to pick some other provider.
     */
    public static final String IGNORE_PROVIDER_EXTRA = "com.android.phone.ProviderToIgnore";

    // string constants
    private static final String NUM_PROJECTION[] = {CommonDataKinds.Phone.NUMBER};

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    private static final String BUTTON_VOICEMAIL_KEY = "button_voicemail_key";
    private static final String BUTTON_VOICEMAIL_CATEGORY_KEY = "button_voicemail_category_key";
    private static final String BUTTON_MWI_NOTIFICATION_KEY = "button_mwi_notification_key";
    private static final String BUTTON_VOICEMAIL_PROVIDER_KEY = "button_voicemail_provider_key";
    private static final String BUTTON_VOICEMAIL_SETTING_KEY = "button_voicemail_setting_key";
    private static final String BUTTON_HAC_KEY         = "button_hac_key";
    private static final String BUTTON_NOISE_SUPPRESSION_KEY = "button_noise_suppression_key";
    private static final String BUTTON_T9_SEARCH_INPUT_LOCALE = "button_t9_search_input";
    private static final String BUTTON_RESPOND_VIA_SMS_KEY   = "button_respond_via_sms_key";

    private static final String BUTTON_RINGTONE_KEY    = "button_ringtone_key";
    private static final String BUTTON_VIBRATE_ON_RING = "button_vibrate_on_ring";
    private static final String BUTTON_PLAY_DTMF_TONE  = "button_play_dtmf_tone";
    private static final String BUTTON_DTMF_KEY        = "button_dtmf_settings";
    private static final String BUTTON_RETRY_KEY       = "button_auto_retry_key";

    private static final String BUTTON_INCOMING_CALL_STYLE = "button_incoming_call_style";

    private static final String BUTTON_PROXIMITY_KEY   = "button_proximity_key";
    private static final String BUTTON_IPPREFIX_KEY = "button_ipprefix_key";
    private static final String BUTTON_EMERGENCY_CALL_KEY = "emergency_call_list";

    protected static final String BUTTON_GSM_UMTS_OPTIONS = "button_gsm_more_expand_key";
    protected static final String BUTTON_CDMA_OPTIONS = "button_cdma_more_expand_key";
    protected static final String BUTTON_FDN_KEY   = "button_fdn_key";
    protected static final String BUTTON_TTY_KEY       = "button_tty_mode_key";

    // New preference key for voicemail notification vibration
    /* package */ static final String BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_KEY =
            "button_voicemail_notification_vibrate_key";
    // Old preference key for voicemail notification vibration. Used for migration to the new
    // preference key only.
    /* package */ static final String BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_WHEN_KEY =
            "button_voicemail_notification_vibrate_when_key";
    /* package */ static final String BUTTON_VOICEMAIL_NOTIFICATION_RINGTONE_KEY =
            "button_voicemail_notification_ringtone_key";

    private static final String VM_NUMBERS_SHARED_PREFERENCES_NAME = "vm_numbers";

    private static final String BUTTON_SIP_CALL_OPTIONS =
            "sip_call_options_key";
    private static final String BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY =
            "sip_call_options_wifi_only_key";
    private static final String SIP_SETTINGS_CATEGORY_KEY =
            "sip_settings_category_key";

    private static final String SWITCH_ENABLE_FORWARD_LOOKUP =
            "switch_enable_forward_lookup";
    private static final String SWITCH_ENABLE_PEOPLE_LOOKUP =
            "switch_enable_people_lookup";
    private static final String SWITCH_ENABLE_REVERSE_LOOKUP =
            "switch_enable_reverse_lookup";
    private static final String BUTTON_CHOOSE_FORWARD_LOOKUP_PROVIDER =
            "button_choose_forward_lookup_provider";
    private static final String BUTTON_CHOOSE_PEOPLE_LOOKUP_PROVIDER =
            "button_choose_people_lookup_provider";
    private static final String BUTTON_CHOOSE_REVERSE_LOOKUP_PROVIDER =
            "button_choose_reverse_lookup_provider";

    private Intent mContactListIntent;

    /** Event for Async voicemail change call */
    private static final int EVENT_VOICEMAIL_CHANGED        = 500;
    private static final int EVENT_FORWARDING_CHANGED       = 501;
    private static final int EVENT_FORWARDING_GET_COMPLETED = 502;

    private static final int MSG_UPDATE_RINGTONE_SUMMARY = 1;
    private static final int MSG_UPDATE_VOICEMAIL_RINGTONE_SUMMARY = 2;

    // preferred TTY mode
    // Phone.TTY_MODE_xxx
    static final int preferredTtyMode = Phone.TTY_MODE_OFF;

    // dialog identifiers for TTY
    private static final int TTY_SET_RESPONSE_ERROR = 800;

    public static final String HAC_KEY = "HACSetting";
    public static final String HAC_VAL_ON = "ON";
    public static final String HAC_VAL_OFF = "OFF";

    /** Handle to voicemail pref */
    private static final int VOICEMAIL_PREF_ID = 1;
    private static final int VOICEMAIL_PROVIDER_CFG_ID = 2;

    // t9 search input locales that we have a custom overlay for
    private static final Locale[] T9_SEARCH_INPUT_LOCALES = new Locale[] {
            new Locale("ko"), new Locale("el"), new Locale("ru"),
            new Locale("he"), new Locale("zh")
    };

    // keys of preferences which should be disabled in airplane mode
    private static final List<String> DISABLE_IN_AIRPLANE_MODE_PREFS = Arrays.asList(
            BUTTON_VOICEMAIL_PROVIDER_KEY, BUTTON_VOICEMAIL_SETTING_KEY,
            BUTTON_GSM_UMTS_OPTIONS, BUTTON_CDMA_OPTIONS,
            BUTTON_TTY_KEY, BUTTON_FDN_KEY,
            // GSM specific options
            "button_cf_expand_key", "button_more_expand_key",
            // CDMA specific options
            "button_voice_privacy_key"
    );

    protected Phone mPhone;
    protected AudioManager mAudioManager;
    protected SipManager mSipManager;
    protected int mSubscription = -1;

    private static final int VM_NOCHANGE_ERROR = 400;
    private static final int VM_RESPONSE_ERROR = 500;
    private static final int FW_SET_RESPONSE_ERROR = 501;
    private static final int FW_GET_RESPONSE_ERROR = 502;


    // dialog identifiers for voicemail
    private static final int VOICEMAIL_DIALOG_CONFIRM = 600;
    private static final int VOICEMAIL_FWD_SAVING_DIALOG = 601;
    private static final int VOICEMAIL_FWD_READING_DIALOG = 602;
    private static final int VOICEMAIL_REVERTING_DIALOG = 603;

    // status message sent back from handlers
    private static final int MSG_OK = 100;

    // special statuses for voicemail controls.
    private static final int MSG_VM_EXCEPTION = 400;
    private static final int MSG_FW_SET_EXCEPTION = 401;
    private static final int MSG_FW_GET_EXCEPTION = 402;
    private static final int MSG_VM_OK = 600;
    private static final int MSG_VM_NOCHANGE = 700;

    // voicemail notification vibration string constants
    private static final String VOICEMAIL_VIBRATION_ALWAYS = "always";
    private static final String VOICEMAIL_VIBRATION_NEVER = "never";
    private PreferenceScreen mIPPrefix;

    // Blacklist support
    private static final String BUTTON_BLACKLIST = "button_blacklist";

    // Call recording format
    private static final String CALL_RECORDING_FORMAT = "call_recording_format";

    private EditPhoneNumberPreference mSubMenuVoicemailSettings;

    private Runnable mRingtoneLookupRunnable;
    private final Handler mRingtoneLookupComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_RINGTONE_SUMMARY:
                    mRingtonePreference.setSummary((CharSequence) msg.obj);
                    break;
                case MSG_UPDATE_VOICEMAIL_RINGTONE_SUMMARY:
                    mVoicemailNotificationRingtone.setSummary((CharSequence) msg.obj);
                    break;
            }
        }
    };

    private DefaultRingtonePreference mRingtonePreference;
    private PreferenceScreen mEmergencyCall;
    protected CheckBoxPreference mVibrateWhenRinging;
    private ListPreference mIncomingCallStyle;
    /** Whether dialpad plays DTMF tone or not. */
    private CheckBoxPreference mPlayDtmfTone;
    private CheckBoxPreference mButtonAutoRetry;
    private CheckBoxPreference mButtonHAC;
    private ListPreference mButtonDTMF;
    private ListPreference mButtonTTY;
    private CheckBoxPreference mButtonNoiseSuppression;
    private ListPreference mButtonSipCallOptions;
    private CheckBoxPreference mMwiNotification;
    private PreferenceScreen mVoicemailCategory;
    private ListPreference mVoicemailProviders;
    private PreferenceScreen mVoicemailSettings;
    private Preference mVoicemailNotificationRingtone;
    private CheckBoxPreference mVoicemailNotificationVibrate;
    private SipSharedPreferences mSipSharedPreferences;
    private PreferenceScreen mButtonBlacklist;
    private CheckBoxPreference mEnableForwardLookup;
    private CheckBoxPreference mEnablePeopleLookup;
    private CheckBoxPreference mEnableReverseLookup;
    private ListPreference mChooseForwardLookupProvider;
    private ListPreference mChoosePeopleLookupProvider;
    private ListPreference mChooseReverseLookupProvider;
    private ListPreference mT9SearchInputLocale;
    private CheckBoxPreference mButtonProximity;
    private ListPreference mCallRecordingFormat;

    private class VoiceMailProvider {
        public VoiceMailProvider(String name, Intent intent) {
            this.name = name;
            this.intent = intent;
        }
        public String name;
        public Intent intent;
    }

    /**
     * Forwarding settings we are going to save.
     */
    private static final int [] FORWARDING_SETTINGS_REASONS = new int[] {
            CommandsInterface.CF_REASON_UNCONDITIONAL,
            CommandsInterface.CF_REASON_BUSY,
            CommandsInterface.CF_REASON_NO_REPLY,
            CommandsInterface.CF_REASON_NOT_REACHABLE
    };

    private class VoiceMailProviderSettings {
        /**
         * Constructs settings object, setting all conditional forwarding to the specified number
         */
        public VoiceMailProviderSettings(String voicemailNumber, String forwardingNumber,
                int timeSeconds) {
            this.voicemailNumber = voicemailNumber;
            if (forwardingNumber == null || forwardingNumber.length() == 0) {
                this.forwardingSettings = FWD_SETTINGS_DONT_TOUCH;
            } else {
                this.forwardingSettings = new CallForwardInfo[FORWARDING_SETTINGS_REASONS.length];
                for (int i = 0; i < this.forwardingSettings.length; i++) {
                    CallForwardInfo fi = new CallForwardInfo();
                    this.forwardingSettings[i] = fi;
                    fi.reason = FORWARDING_SETTINGS_REASONS[i];
                    fi.status = (fi.reason == CommandsInterface.CF_REASON_UNCONDITIONAL) ? 0 : 1;
                    fi.serviceClass = CommandsInterface.SERVICE_CLASS_VOICE;
                    fi.toa = PhoneNumberUtils.TOA_International;
                    fi.number = forwardingNumber;
                    fi.timeSeconds = timeSeconds;
                }
            }
        }

        public VoiceMailProviderSettings(String voicemailNumber, CallForwardInfo[] infos) {
            this.voicemailNumber = voicemailNumber;
            this.forwardingSettings = infos;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) return false;
            if (!(o instanceof VoiceMailProviderSettings)) return false;
            final VoiceMailProviderSettings v = (VoiceMailProviderSettings)o;

            return ((this.voicemailNumber == null &&
                    v.voicemailNumber == null) ||
                    this.voicemailNumber != null &&
                            this.voicemailNumber.equals(v.voicemailNumber))
                    &&
                    forwardingSettingsEqual(this.forwardingSettings,
                            v.forwardingSettings);
        }

        private boolean forwardingSettingsEqual(CallForwardInfo[] infos1,
                CallForwardInfo[] infos2) {
            if (infos1 == infos2) return true;
            if (infos1 == null || infos2 == null) return false;
            if (infos1.length != infos2.length) return false;
            for (int i = 0; i < infos1.length; i++) {
                CallForwardInfo i1 = infos1[i];
                CallForwardInfo i2 = infos2[i];
                if (i1.status != i2.status ||
                        i1.reason != i2.reason ||
                        i1.serviceClass != i2.serviceClass ||
                        i1.toa != i2.toa ||
                        i1.number != i2.number ||
                        i1.timeSeconds != i2.timeSeconds) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return voicemailNumber + ((forwardingSettings != null ) ? (", " +
                    forwardingSettings.toString()) : "");
        }

        public String voicemailNumber;
        public CallForwardInfo[] forwardingSettings;
    }

    private SharedPreferences mPerProviderSavedVMNumbers;

    /**
     * Results of reading forwarding settings
     */
    private CallForwardInfo[] mForwardingReadResults = null;

    /**
     * Result of forwarding number change.
     * Keys are reasons (eg. unconditional forwarding).
     */
    private Map<Integer, AsyncResult> mForwardingChangeResults = null;

    /**
     * Expected CF read result types.
     * This set keeps track of the CF types for which we've issued change
     * commands so we can tell when we've received all of the responses.
     */
    private Collection<Integer> mExpectedChangeResultReasons = null;

    /**
     * Result of vm number change
     */
    private AsyncResult mVoicemailChangeResult = null;

    /**
     * Previous VM provider setting so we can return to it in case of failure.
     */
    private String mPreviousVMProviderKey = null;

    /**
     * Id of the dialog being currently shown.
     */
    private int mCurrentDialogId = 0;

    /**
     * Flag indicating that we are invoking settings for the voicemail provider programmatically
     * due to vm provider change.
     */
    private boolean mVMProviderSettingsForced = false;

    /**
     * Flag indicating that we are making changes to vm or fwd numbers
     * due to vm provider change.
     */
    private boolean mChangingVMorFwdDueToProviderChange = false;

    /**
     * True if we are in the process of vm & fwd number change and vm has already been changed.
     * This is used to decide what to do in case of rollback.
     */
    private boolean mVMChangeCompletedSuccessfully = false;

    /**
     * True if we had full or partial failure setting forwarding numbers and so need to roll them
     * back.
     */
    private boolean mFwdChangesRequireRollback = false;

    /**
     * Id of error msg to display to user once we are done reverting the VM provider to the previous
     * one.
     */
    private int mVMOrFwdSetError = 0;

    /**
     * Data about discovered voice mail settings providers.
     * Is populated by querying which activities can handle ACTION_CONFIGURE_VOICEMAIL.
     * They key in this map is package name + activity name.
     * We always add an entry for the default provider with a key of empty
     * string and intent value of null.
     * @see #initVoiceMailProviders()
     */
    private final Map<String, VoiceMailProvider> mVMProvidersData =
            new HashMap<String, VoiceMailProvider>();

    /** string to hold old voicemail number as it is being updated. */
    private String mOldVmNumber;

    // New call forwarding settings and vm number we will be setting
    // Need to save these since before we get to saving we need to asynchronously
    // query the existing forwarding settings.
    private CallForwardInfo[] mNewFwdSettings;
    private String mNewVMNumber;

    private boolean mForeground;

    @Override
    public void onPause() {
        super.onPause();
        mForeground = false;
    }

    /**
     * We have to pull current settings from the network for all kinds of
     * voicemail providers so we can tell whether we have to update them,
     * so use this bit to keep track of whether we're reading settings for the
     * default provider and should therefore save them out when done.
     */
    private boolean mReadingSettingsForDefaultProvider = false;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mIPPrefix) {
            View v = getLayoutInflater().inflate(R.layout.ip_prefix, null);
            final EditText edit = (EditText) v.findViewById(R.id.ip_prefix_dialog_edit);
            String ip_prefix = Settings.System.getString(getContentResolver(),
                    Constants.SETTINGS_IP_PREFIX + 1);
            edit.setText(ip_prefix);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.ipcall_dialog_title)
                    .setView(v)
                    .setPositiveButton(android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    String ip_prefix = edit.getText().toString();
                                    Settings.System.putString(getContentResolver(),
                                            Constants.SETTINGS_IP_PREFIX + 1, ip_prefix);
                                    if (TextUtils.isEmpty(ip_prefix)) {
                                        mIPPrefix.setSummary(
                                                R.string.ipcall_sub_summery);
                                    } else {
                                        mIPPrefix.setSummary(edit.getText());
                                    }
                                    onResume();
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        } else if (preference == mPlayDtmfTone) {
            Settings.System.putInt(getContentResolver(), Settings.System.DTMF_TONE_WHEN_DIALING,
                    mPlayDtmfTone.isChecked() ? 1 : 0);
        } else if (preference == mMwiNotification) {
            int mwiNotification = mMwiNotification.isChecked() ? 1 : 0;
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.ENABLE_MWI_NOTIFICATION, mwiNotification);
            return true;
        } else if (preference == mButtonDTMF) {
            return true;
        } else if (preference == mButtonTTY) {
            if (PhoneUtils.isImsVtCallPresent()) {
                // TTY Mode change is not allowed during a VT call
                showDialog(TTY_SET_RESPONSE_ERROR);
            }
            return true;
        } else if (preference == mButtonNoiseSuppression) {
            int nsp = mButtonNoiseSuppression.isChecked() ? 1 : 0;
            // Update Noise suppression value in Settings database
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.NOISE_SUPPRESSION, nsp);
            return true;
        } else if (preference == mButtonAutoRetry) {
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        } else if (preference == mButtonHAC) {
            int hac = mButtonHAC.isChecked() ? 1 : 0;
            // Update HAC value in Settings database
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.HEARING_AID, hac);

            // Update HAC Value in AudioManager
            mAudioManager.setParameter(HAC_KEY, hac != 0 ? HAC_VAL_ON : HAC_VAL_OFF);
            return true;
        } else if (preference == mSubMenuVoicemailSettings) {
            return true;
        } else if (preference == mVoicemailSettings) {
            log("onPreferenceTreeClick: Voicemail Settings Preference is clicked.");
            if (preference.getIntent() != null) {
                log("onPreferenceTreeClick: Invoking cfg intent "
                        + preference.getIntent().getPackage());

                // onActivityResult() will be responsible for resetting some of variables.
                this.startActivityForResult(preference.getIntent(), VOICEMAIL_PROVIDER_CFG_ID);
                return true;
            } else {
                log("onPreferenceTreeClick:"
                        + " No Intent is available. Use default behavior defined in xml.");

                // There's no onActivityResult(), so we need to take care of some of variables
                // which should be reset here.
                mPreviousVMProviderKey = DEFAULT_VM_PROVIDER_KEY;
                mVMProviderSettingsForced = false;

                // This should let the preference use default behavior in the xml.
                return false;
            }
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        log("onPreferenceChange(). preferenece: \"" + preference + "\""
                + ", value: \"" + objValue + "\"");
        if (preference == mVibrateWhenRinging) {
            boolean doVibrate = (Boolean) objValue;
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.VIBRATE_WHEN_RINGING, doVibrate ? 1 : 0);
        } else if (preference == mButtonDTMF) {
            int index = mButtonDTMF.findIndexOfValue((String) objValue);
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
        } else if (preference == mIncomingCallStyle) {
            int index = mIncomingCallStyle.findIndexOfValue((String) objValue);
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.INCOMING_CALL_STYLE, index);
        } else if (preference == mButtonTTY) {
            handleTTYChange(preference, objValue);
        } else if (preference == mButtonProximity) {
            boolean checked = (Boolean) objValue;
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Constants.SETTINGS_PROXIMITY_SENSOR, checked ? 1 : 0);
            mButtonProximity.setSummary(checked ? R.string.proximity_on_summary
                    : R.string.proximity_off_summary);
        } else if (preference == mVoicemailProviders) {
            final String newProviderKey = (String) objValue;
            log("Voicemail Provider changes from \"" + mPreviousVMProviderKey
                    + "\" to \"" + newProviderKey + "\".");
            // If previous provider key and the new one is same, we don't need to handle it.
            if (mPreviousVMProviderKey.equals(newProviderKey)) {
                log("No change is made toward VM provider setting.");
                return true;
            }
            updateVMPreferenceWidgets(newProviderKey);

            final VoiceMailProviderSettings newProviderSettings =
                    loadSettingsForVoiceMailProvider(newProviderKey);

            // If the user switches to a voice mail provider and we have a
            // numbers stored for it we will automatically change the
            // phone's
            // voice mail and forwarding number to the stored ones.
            // Otherwise we will bring up provider's configuration UI.

            if (newProviderSettings == null) {
                // Force the user into a configuration of the chosen provider
                Log.w(LOG_TAG, "Saved preferences not found - invoking config");
                mVMProviderSettingsForced = true;
                simulatePreferenceClick(mVoicemailSettings);
            } else {
                log("Saved preferences found - switching to them");
                // Set this flag so if we get a failure we revert to previous provider
                mChangingVMorFwdDueToProviderChange = true;
                saveVoiceMailAndForwardingNumber(newProviderKey, newProviderSettings);
            }
        } else if (preference == mButtonSipCallOptions) {
            handleSipCallOptionsChange(objValue);
        } else if (preference == mEnableForwardLookup
                || preference == mEnablePeopleLookup
                || preference == mEnableReverseLookup) {
            saveLookupProviderSwitch(preference, (Boolean) objValue);
        } else if (preference == mChooseForwardLookupProvider
                || preference == mChoosePeopleLookupProvider
                || preference == mChooseReverseLookupProvider) {
            saveLookupProviderSetting(preference, (String) objValue);
        } else if (preference == mT9SearchInputLocale) {
            saveT9SearchInputLocale(preference, (String) objValue);
        } else if (preference == mCallRecordingFormat) {
            int value = Integer.valueOf((String) objValue);
            int index = mCallRecordingFormat.findIndexOfValue((String) objValue);
            Settings.System.putInt(getContentResolver(), Settings.System.CALL_RECORDING_FORMAT, value);
            mCallRecordingFormat.setSummary(mCallRecordingFormat.getEntries()[index]);
        }
        // always let the preference setting proceed.
        return true;
    }

    @Override
    public void onDialogClosed(EditPhoneNumberPreference preference, int buttonClicked) {
        log("onPreferenceClick: request preference click on dialog close: " +
                buttonClicked);
        if (buttonClicked == DialogInterface.BUTTON_NEGATIVE) {
            return;
        }

        if (preference == mSubMenuVoicemailSettings) {
            handleVMBtnClickRequest();
        }
    }

    /**
     * Implemented for EditPhoneNumberPreference.GetDefaultNumberListener.
     * This method set the default values for the various
     * EditPhoneNumberPreference dialogs.
     */
    @Override
    public String onGetDefaultNumber(EditPhoneNumberPreference preference) {
        if (preference == mSubMenuVoicemailSettings) {
            // update the voicemail number field, which takes care of the
            // mSubMenuVoicemailSettings itself, so we should return null.
            log("updating default for voicemail dialog");
            updateVoiceNumberField();
            return null;
        }

        String vmDisplay = mPhone.getVoiceMailNumber();
        if (TextUtils.isEmpty(vmDisplay)) {
            // if there is no voicemail number, we just return null to
            // indicate no contribution.
            return null;
        }

        // Return the voicemail number prepended with "VM: "
        if (DBG) log("updating default for call forwarding dialogs");
        return getString(R.string.voicemail_abbreviated) + " " + vmDisplay;
    }


    // override the startsubactivity call to make changes in state consistent.
    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        if (requestCode == -1) {
            // this is an intent requested from the preference framework.
            super.startActivityForResult(intent, requestCode);
            return;
        }

        log("startSubActivity: starting requested subactivity");
        super.startActivityForResult(intent, requestCode);
    }

    private void switchToPreviousVoicemailProvider() {
        log("switchToPreviousVoicemailProvider " + mPreviousVMProviderKey);
        if (mPreviousVMProviderKey != null) {
            if (mVMChangeCompletedSuccessfully || mFwdChangesRequireRollback) {
                // we have to revert with carrier
                log("Needs to rollback."
                        + " mVMChangeCompletedSuccessfully=" + mVMChangeCompletedSuccessfully
                        + ", mFwdChangesRequireRollback=" + mFwdChangesRequireRollback);

                showDialogIfForeground(VOICEMAIL_REVERTING_DIALOG);
                final VoiceMailProviderSettings prevSettings =
                        loadSettingsForVoiceMailProvider(mPreviousVMProviderKey);
                if (prevSettings == null) {
                    // prevSettings never becomes null since it should be already loaded!
                    Log.e(LOG_TAG, "VoiceMailProviderSettings for the key \""
                            + mPreviousVMProviderKey + "\" becomes null, which is unexpected.");
                    if (DBG) {
                        Log.e(LOG_TAG,
                                "mVMChangeCompletedSuccessfully: " + mVMChangeCompletedSuccessfully
                                        + ", mFwdChangesRequireRollback: " + mFwdChangesRequireRollback);
                    }
                }
                if (mVMChangeCompletedSuccessfully) {
                    mNewVMNumber = prevSettings.voicemailNumber;
                    log("VM change is already completed successfully."
                            + "Have to revert VM back to " + mNewVMNumber + " again.");
                    mPhone.setVoiceMailNumber(
                            mPhone.getVoiceMailAlphaTag().toString(),
                            mNewVMNumber,
                            Message.obtain(mRevertOptionComplete, EVENT_VOICEMAIL_CHANGED));
                }
                if (mFwdChangesRequireRollback) {
                    log("Requested to rollback Fwd changes.");
                    final CallForwardInfo[] prevFwdSettings =
                            prevSettings.forwardingSettings;
                    if (prevFwdSettings != null) {
                        Map<Integer, AsyncResult> results =
                                mForwardingChangeResults;
                        resetForwardingChangeState();
                        for (int i = 0; i < prevFwdSettings.length; i++) {
                            CallForwardInfo fi = prevFwdSettings[i];
                            log("Reverting fwd #: " + i + ": " + fi.toString());
                            // Only revert the settings for which the update
                            // succeeded
                            AsyncResult result = results.get(fi.reason);
                            if (result != null && result.exception == null) {
                                mExpectedChangeResultReasons.add(fi.reason);
                                mPhone.setCallForwardingOption(
                                        (fi.status == 1 ?
                                                CommandsInterface.CF_ACTION_REGISTRATION :
                                                CommandsInterface.CF_ACTION_DISABLE),
                                        fi.reason,
                                        fi.number,
                                        fi.timeSeconds,
                                        mRevertOptionComplete.obtainMessage(
                                                EVENT_FORWARDING_CHANGED, i, 0));
                            }
                        }
                    }
                }
            } else {
                log("No need to revert");
                onRevertDone();
            }
        }
    }

    private void onRevertDone() {
        log("Flipping provider key back to " + mPreviousVMProviderKey);
        mVoicemailProviders.setValue(mPreviousVMProviderKey);
        updateVMPreferenceWidgets(mPreviousVMProviderKey);
        updateVoiceNumberField();
        if (mVMOrFwdSetError != 0) {
            showVMDialog(mVMOrFwdSetError);
            mVMOrFwdSetError = 0;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        log("onActivityResult: requestCode: " + requestCode
                + ", resultCode: " + resultCode
                + ", data: " + data);
        // there are cases where the contact picker may end up sending us more than one
        // request.  We want to ignore the request if we're not in the correct state.
        if (requestCode == VOICEMAIL_PROVIDER_CFG_ID) {
            boolean failure = false;

            // No matter how the processing of result goes lets clear the flag
            log("mVMProviderSettingsForced: " + mVMProviderSettingsForced);
            final boolean isVMProviderSettingsForced = mVMProviderSettingsForced;
            mVMProviderSettingsForced = false;

            String vmNum = null;
            if (resultCode != RESULT_OK) {
                log("onActivityResult: vm provider cfg result not OK.");
                failure = true;
            } else {
                if (data == null) {
                    log("onActivityResult: vm provider cfg result has no data");
                    failure = true;
                } else {
                    if (data.getBooleanExtra(SIGNOUT_EXTRA, false)) {
                        log("Provider requested signout");
                        if (isVMProviderSettingsForced) {
                            log("Going back to previous provider on signout");
                            switchToPreviousVoicemailProvider();
                        } else {
                            final String victim = getCurrentVoicemailProviderKey();
                            log("Relaunching activity and ignoring " + victim);
                            Intent i = new Intent(ACTION_ADD_VOICEMAIL);
                            i.putExtra(IGNORE_PROVIDER_EXTRA, victim);
                            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            this.startActivity(i);
                        }
                        return;
                    }
                    vmNum = data.getStringExtra(VM_NUMBER_EXTRA);
                    if (vmNum == null || vmNum.length() == 0) {
                        log("onActivityResult: vm provider cfg result has no vmnum");
                        failure = true;
                    }
                }
            }
            if (failure) {
                log("Failure in return from voicemail provider");
                if (isVMProviderSettingsForced) {
                    switchToPreviousVoicemailProvider();
                } else {
                    log("Not switching back the provider since this is not forced config");
                }
                return;
            }
            mChangingVMorFwdDueToProviderChange = isVMProviderSettingsForced;
            final String fwdNum = data.getStringExtra(FWD_NUMBER_EXTRA);

            // TODO(iliat): It would be nice to load the current network setting for this and
            // send it to the provider when it's config is invoked so it can use this as default
            final int fwdNumTime = data.getIntExtra(FWD_NUMBER_TIME_EXTRA, 20);

            log("onActivityResult: vm provider cfg result " +
                    (fwdNum != null ? "has" : " does not have") + " forwarding number");
            saveVoiceMailAndForwardingNumber(getCurrentVoicemailProviderKey(),
                    new VoiceMailProviderSettings(vmNum, fwdNum, fwdNumTime));
            return;
        }

        if (requestCode == VOICEMAIL_PREF_ID) {
            if (resultCode != RESULT_OK) {
                log("onActivityResult: contact picker result not OK.");
                return;
            }

            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(data.getData(),
                        NUM_PROJECTION, null, null, null);
                if ((cursor == null) || (!cursor.moveToFirst())) {
                    log("onActivityResult: bad contact data, no results found.");
                    return;
                }
                mSubMenuVoicemailSettings.onPickActivityResult(cursor.getString(0));
                return;
            } catch (SQLiteException sqle) {
                    log("onActivityResult: bad contact data, no results found.");
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    // Voicemail button logic
    private void handleVMBtnClickRequest() {
        // normally called on the dialog close.

        // Since we're stripping the formatting out on the getPhoneNumber()
        // call now, we won't need to do so here anymore.

        saveVoiceMailAndForwardingNumber(
                getCurrentVoicemailProviderKey(),
                new VoiceMailProviderSettings(mSubMenuVoicemailSettings.getPhoneNumber(),
                        FWD_SETTINGS_DONT_TOUCH));
    }


    /**
     * Wrapper around showDialog() that will silently do nothing if we're
     * not in the foreground.
     *
     * This is useful here because most of the dialogs we display from
     * this class are triggered by asynchronous events (like
     * success/failure messages from the telephony layer) and it's
     * possible for those events to come in even after the user has gone
     * to a different screen.
     */
    // TODO: this is too brittle: it's still easy to accidentally add new
    // code here that calls showDialog() directly (which will result in a
    // WindowManager$BadTokenException if called after the activity has
    // been stopped.)
    //
    // It would be cleaner to do the "if (mForeground)" check in one
    // central place, maybe by using a single Handler for all asynchronous
    // events (and have *that* discard events if we're not in the
    // foreground.)
    //
    // Unfortunately it's not that simple, since we sometimes need to do
    // actual work to handle these events whether or not we're in the
    // foreground (see the Handler code in mSetOptionComplete for
    // example.)
    private void showDialogIfForeground(int id) {
        if (mForeground) {
            showDialog(id);
        }
    }

    private void dismissDialogSafely(int id) {
        try {
            dismissDialog(id);
        } catch (IllegalArgumentException e) {
            // This is expected in the case where we were in the background
            // at the time we would normally have shown the dialog, so we didn't
            // show it.
        }
    }

    private void saveVoiceMailAndForwardingNumber(String key,
            VoiceMailProviderSettings newSettings) {
        log("saveVoiceMailAndForwardingNumber: " + newSettings.toString());
        mNewVMNumber = newSettings.voicemailNumber;
        // empty vm number == clearing the vm number ?
        if (mNewVMNumber == null) {
            mNewVMNumber = "";
        }

        mNewFwdSettings = newSettings.forwardingSettings;
        log("newFwdNumber " +
                String.valueOf((mNewFwdSettings != null ? mNewFwdSettings.length : 0))
                + " settings");

        // No fwd settings on CDMA
        if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            log("ignoring forwarding setting since this is CDMA phone");
            mNewFwdSettings = FWD_SETTINGS_DONT_TOUCH;
        }

        //throw a warning if the vm is the same and we do not touch forwarding.
        if (mNewVMNumber.equals(mOldVmNumber) && mNewFwdSettings == FWD_SETTINGS_DONT_TOUCH) {
            showVMDialog(MSG_VM_NOCHANGE);
            return;
        }

        maybeSaveSettingsForVoicemailProvider(key, newSettings);
        mVMChangeCompletedSuccessfully = false;
        mFwdChangesRequireRollback = false;
        mVMOrFwdSetError = 0;
        if (!key.equals(mPreviousVMProviderKey)) {
            mReadingSettingsForDefaultProvider =
                    mPreviousVMProviderKey.equals(DEFAULT_VM_PROVIDER_KEY);
            log("Reading current forwarding settings");
            mForwardingReadResults = new CallForwardInfo[FORWARDING_SETTINGS_REASONS.length];
            for (int i = 0; i < FORWARDING_SETTINGS_REASONS.length; i++) {
                mForwardingReadResults[i] = null;
                final int reason = FORWARDING_SETTINGS_REASONS[i];
                mPhone.getCallForwardingOption(reason,
                        mGetOptionComplete
                                .obtainMessage(EVENT_FORWARDING_GET_COMPLETED, reason, 0));
            }
            showDialogIfForeground(VOICEMAIL_FWD_READING_DIALOG);
        } else {
            saveVoiceMailAndForwardingNumberStage2();
        }
    }

    private final Handler mGetOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult result = (AsyncResult) msg.obj;
            switch (msg.what) {
                case EVENT_FORWARDING_GET_COMPLETED:
                    handleForwardingSettingsReadResult(result, msg.arg1);
                    break;
            }
        }
    };

    private void handleForwardingSettingsReadResult(AsyncResult ar, int idx) {
        log("handleForwardingSettingsReadResult: " + idx);
        Throwable error = null;
        if (ar.exception != null) {
            log("FwdRead: ar.exception=" +
                    ar.exception.getMessage());
            error = ar.exception;
        }
        if (ar.userObj instanceof Throwable) {
            log("FwdRead: userObj=" +
                    ((Throwable)ar.userObj).getMessage());
            error = (Throwable)ar.userObj;
        }

        // We may have already gotten an error and decided to ignore the other results.
        if (mForwardingReadResults == null) {
            log("ignoring fwd reading result: " + idx);
            return;
        }

        // In case of error ignore other results, show an error dialog
        if (error != null) {
            log("Error discovered for fwd read : " + idx);
            mForwardingReadResults = null;
            dismissDialogSafely(VOICEMAIL_FWD_READING_DIALOG);
            showVMDialog(MSG_FW_GET_EXCEPTION);
            return;
        }

        // Get the forwarding info
        final CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
        CallForwardInfo fi = null;
        for (int i = 0 ; i < cfInfoArray.length; i++) {
            if ((CommandsInterface.SERVICE_CLASS_VOICE & cfInfoArray[i].serviceClass) != 0) {
                fi = cfInfoArray[i];
                /*
                 * XXX: The RIL often returns reason 0 for any request.
                 * This causes errors when restoring a VM provider.
                 */
                fi.reason = idx;
                break;
            }
        }
        if (fi == null) {

            // In case we go nothing it means we need this reason disabled
            // so create a CallForwardInfo for capturing this
            log("Creating default info for " + idx);
            fi = new CallForwardInfo();
            fi.status = 0;
            fi.reason = FORWARDING_SETTINGS_REASONS[idx];
            fi.serviceClass = CommandsInterface.SERVICE_CLASS_VOICE;
        } else {
            // if there is not a forwarding number, ensure the entry is set to "not active."
            if (fi.number == null || fi.number.length() == 0) {
                fi.status = 0;
            }

            log( "Got  " + fi.toString() + " for " + idx);
        }
        mForwardingReadResults[idx] = fi;

        // Check if we got all the results already
        boolean done = true;
        for (int i = 0; i < mForwardingReadResults.length; i++) {
            if (mForwardingReadResults[i] == null) {
                done = false;
                break;
            }
        }
        if (done) {
            log("Done receiving fwd info");
            dismissDialogSafely(VOICEMAIL_FWD_READING_DIALOG);
            if (mReadingSettingsForDefaultProvider) {
                maybeSaveSettingsForVoicemailProvider(DEFAULT_VM_PROVIDER_KEY,
                        new VoiceMailProviderSettings(this.mOldVmNumber,
                                mForwardingReadResults));
                mReadingSettingsForDefaultProvider = false;
            }
            saveVoiceMailAndForwardingNumberStage2();
        } else {
            log("Not done receiving fwd info");
        }
    }

    private CallForwardInfo infoForReason(CallForwardInfo[] infos, int reason) {
        CallForwardInfo result = null;
        if (null != infos) {
            for (CallForwardInfo info : infos) {
                if (info.reason == reason) {
                    result = info;
                    break;
                }
            }
        }
        return result;
    }

    private boolean isUpdateRequired(CallForwardInfo oldInfo,
            CallForwardInfo newInfo) {
        boolean result = true;
        if (0 == newInfo.status) {
            // If we're disabling a type of forwarding, and it's already
            // disabled for the account, don't make any change
            if (oldInfo != null && oldInfo.status == 0) {
                result = false;
            }
        }
        return result;
    }

    private void resetForwardingChangeState() {
        mForwardingChangeResults = new HashMap<Integer, AsyncResult>();
        mExpectedChangeResultReasons = new HashSet<Integer>();
    }

    // Called after we are done saving the previous forwarding settings if
    // we needed.
    private void saveVoiceMailAndForwardingNumberStage2() {
        mForwardingChangeResults = null;
        mVoicemailChangeResult = null;
        if (mNewFwdSettings != FWD_SETTINGS_DONT_TOUCH) {
            resetForwardingChangeState();
            for (int i = 0; i < mNewFwdSettings.length; i++) {
                CallForwardInfo fi = mNewFwdSettings[i];

                final boolean doUpdate = isUpdateRequired(infoForReason(
                        mForwardingReadResults, fi.reason), fi);

                if (doUpdate) {
                    log("Setting fwd #: " + i + ": " + fi.toString());
                    mExpectedChangeResultReasons.add(i);

                    mPhone.setCallForwardingOption(
                            fi.status == 1 ?
                                    CommandsInterface.CF_ACTION_REGISTRATION :
                                    CommandsInterface.CF_ACTION_DISABLE,
                            fi.reason,
                            fi.number,
                            fi.timeSeconds,
                            mSetOptionComplete.obtainMessage(
                                    EVENT_FORWARDING_CHANGED, fi.reason, 0));
                }
            }
            showDialogIfForeground(VOICEMAIL_FWD_SAVING_DIALOG);
        } else {
            log("Not touching fwd #");
            setVMNumberWithCarrier();
        }
    }

    private void setVMNumberWithCarrier() {
        log("save voicemail #: " + mNewVMNumber);
        mPhone.setVoiceMailNumber(
                mPhone.getVoiceMailAlphaTag().toString(),
                mNewVMNumber,
                Message.obtain(mSetOptionComplete, EVENT_VOICEMAIL_CHANGED));
    }

    /**
     * Callback to handle option update completions
     */
    private final Handler mSetOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult result = (AsyncResult) msg.obj;
            boolean done = false;
            switch (msg.what) {
                case EVENT_VOICEMAIL_CHANGED:
                    mVoicemailChangeResult = result;
                    mVMChangeCompletedSuccessfully = checkVMChangeSuccess() == null;
                    log("VM change complete msg, VM change done = " +
                            String.valueOf(mVMChangeCompletedSuccessfully));
                    done = true;
                    break;
                case EVENT_FORWARDING_CHANGED:
                    mForwardingChangeResults.put(msg.arg1, result);
                    if (result.exception != null) {
                        Log.w(LOG_TAG, "Error in setting fwd# " + msg.arg1 + ": " +
                                result.exception.getMessage());
                    } else {
                        log("Success in setting fwd# " + msg.arg1);
                    }
                    final boolean completed = checkForwardingCompleted();
                    if (completed) {
                        if (checkFwdChangeSuccess() == null) {
                            log("Overall fwd changes completed ok, starting vm change");
                            setVMNumberWithCarrier();
                        } else {
                            Log.w(LOG_TAG, "Overall fwd changes completed in failure. " +
                                    "Check if we need to try rollback for some settings.");
                            mFwdChangesRequireRollback = false;
                            Iterator<Map.Entry<Integer,AsyncResult>> it =
                                    mForwardingChangeResults.entrySet().iterator();
                            while (it.hasNext()) {
                                Map.Entry<Integer,AsyncResult> entry = it.next();
                                if (entry.getValue().exception == null) {
                                    // If at least one succeeded we have to revert
                                    Log.i(LOG_TAG, "Rollback will be required");
                                    mFwdChangesRequireRollback = true;
                                    break;
                                }
                            }
                            if (!mFwdChangesRequireRollback) {
                                Log.i(LOG_TAG, "No rollback needed.");
                            }
                            done = true;
                        }
                    }
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
            if (done) {
                log("All VM provider related changes done");
                if (mForwardingChangeResults != null) {
                    dismissDialogSafely(VOICEMAIL_FWD_SAVING_DIALOG);
                }
                handleSetVMOrFwdMessage();
            }
        }
    };

    /**
     * Callback to handle option revert completions
     */
    private final Handler mRevertOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult result = (AsyncResult) msg.obj;
            switch (msg.what) {
                case EVENT_VOICEMAIL_CHANGED:
                    mVoicemailChangeResult = result;
                    log("VM revert complete msg");
                    break;
                case EVENT_FORWARDING_CHANGED:
                    mForwardingChangeResults.put(msg.arg1, result);
                    if (result.exception != null) {
                        log("Error in reverting fwd# " + msg.arg1 + ": " +
                                result.exception.getMessage());
                    } else {
                        log("Success in reverting fwd# " + msg.arg1);
                    }
                    log("FWD revert complete msg ");
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
            final boolean done =
                    (!mVMChangeCompletedSuccessfully || mVoicemailChangeResult != null) &&
                            (!mFwdChangesRequireRollback || checkForwardingCompleted());
            if (done) {
                log("All VM reverts done");
                dismissDialogSafely(VOICEMAIL_REVERTING_DIALOG);
                onRevertDone();
            }
        }
    };

    /**
     * @return true if forwarding change has completed
     */
    private boolean checkForwardingCompleted() {
        boolean result;
        if (mForwardingChangeResults == null) {
            result = true;
        } else {
            // return true iff there is a change result for every reason for
            // which we expected a result
            result = true;
            for (Integer reason : mExpectedChangeResultReasons) {
                if (mForwardingChangeResults.get(reason) == null) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }
    /**
     * @return error string or null if successful
     */
    private String checkFwdChangeSuccess() {
        String result = null;
        Iterator<Map.Entry<Integer,AsyncResult>> it =
                mForwardingChangeResults.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer,AsyncResult> entry = it.next();
            Throwable exception = entry.getValue().exception;
            if (exception != null) {
                result = exception.getMessage();
                if (result == null) {
                    result = "";
                }
                break;
            }
        }
        return result;
    }

    /**
     * @return error string or null if successful
     */
    private String checkVMChangeSuccess() {
        if (mVoicemailChangeResult.exception != null) {
            final String msg = mVoicemailChangeResult.exception.getMessage();
            if (msg == null) {
                return "";
            }
            return msg;
        }
        return null;
    }

    private void handleSetVMOrFwdMessage() {
        log("handleSetVMMessage: set VM request complete");
        boolean success = true;
        boolean fwdFailure = false;
        String exceptionMessage = "";
        if (mForwardingChangeResults != null) {
            exceptionMessage = checkFwdChangeSuccess();
            if (exceptionMessage != null) {
                success = false;
                fwdFailure = true;
            }
        }
        if (success) {
            exceptionMessage = checkVMChangeSuccess();
            if (exceptionMessage != null) {
                success = false;
            }
        }
        if (success) {
            log("change VM success!");
            handleVMAndFwdSetSuccess(MSG_VM_OK);
        } else {
            if (fwdFailure) {
                Log.w(LOG_TAG, "Failed to change fowarding setting. Reason: " + exceptionMessage);
                handleVMOrFwdSetError(MSG_FW_SET_EXCEPTION);
            } else {
                Log.w(LOG_TAG, "Failed to change voicemail. Reason: " + exceptionMessage);
                handleVMOrFwdSetError(MSG_VM_EXCEPTION);
            }
        }
    }

    /**
     * Called when Voicemail Provider or its forwarding settings failed. Rolls back partly made
     * changes to those settings and show "failure" dialog.
     *
     * @param msgId Message ID used for the specific error case. {@link #MSG_FW_SET_EXCEPTION} or
     * {@link #MSG_VM_EXCEPTION}
     */
    private void handleVMOrFwdSetError(int msgId) {
        if (mChangingVMorFwdDueToProviderChange) {
            mVMOrFwdSetError = msgId;
            mChangingVMorFwdDueToProviderChange = false;
            switchToPreviousVoicemailProvider();
            return;
        }
        mChangingVMorFwdDueToProviderChange = false;
        showVMDialog(msgId);
        updateVoiceNumberField();
    }

    /**
     * Called when Voicemail Provider and its forwarding settings were successfully finished.
     * This updates a bunch of variables and show "success" dialog.
     */
    private void handleVMAndFwdSetSuccess(int msg) {
        log("handleVMAndFwdSetSuccess(). current voicemail provider key: "
                + getCurrentVoicemailProviderKey());
        mPreviousVMProviderKey = getCurrentVoicemailProviderKey();
        mChangingVMorFwdDueToProviderChange = false;
        showVMDialog(msg);
        updateVoiceNumberField();
    }

    /**
     * Update the voicemail number from what we've recorded on the sim.
     */
    private void updateVoiceNumberField() {
        log("updateVoiceNumberField(). mSubMenuVoicemailSettings=" + mSubMenuVoicemailSettings);
        if (mSubMenuVoicemailSettings == null) {
            return;
        }

        mOldVmNumber = mPhone.getVoiceMailNumber();
        if (mOldVmNumber == null) {
            mOldVmNumber = "";
        }
        mSubMenuVoicemailSettings.setPhoneNumber(mOldVmNumber);
        final String summary = (mOldVmNumber.length() > 0) ? mOldVmNumber :
                getString(R.string.voicemail_number_not_set);
        mSubMenuVoicemailSettings.setSummary(summary);
    }

    /*
     * Helper Methods for Activity class.
     * The initial query commands are split into two pieces now
     * for individual expansion.  This combined with the ability
     * to cancel queries allows for a much better user experience,
     * and also ensures that the user only waits to update the
     * data that is relevant.
     */

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        mCurrentDialogId = id;
    }

    // dialog creation method, called by showDialog()
    @Override
    protected Dialog onCreateDialog(int id) {
        if ((id == VM_RESPONSE_ERROR) || (id == VM_NOCHANGE_ERROR) ||
                (id == FW_SET_RESPONSE_ERROR) || (id == FW_GET_RESPONSE_ERROR) ||
                (id == VOICEMAIL_DIALOG_CONFIRM)) {

            AlertDialog.Builder b = new AlertDialog.Builder(this);

            int msgId;
            int titleId = R.string.error_updating_title;
            switch (id) {
                case VOICEMAIL_DIALOG_CONFIRM:
                    msgId = R.string.vm_changed;
                    titleId = R.string.voicemail;
                    // Set Button 2
                    b.setNegativeButton(R.string.close_dialog, this);
                    break;
                case VM_NOCHANGE_ERROR:
                    // even though this is technically an error,
                    // keep the title friendly.
                    msgId = R.string.no_change;
                    titleId = R.string.voicemail;
                    // Set Button 2
                    b.setNegativeButton(R.string.close_dialog, this);
                    break;
                case VM_RESPONSE_ERROR:
                    msgId = R.string.vm_change_failed;
                    // Set Button 1
                    b.setPositiveButton(R.string.close_dialog, this);
                    break;
                case FW_SET_RESPONSE_ERROR:
                    msgId = R.string.fw_change_failed;
                    // Set Button 1
                    b.setPositiveButton(R.string.close_dialog, this);
                    break;
                case FW_GET_RESPONSE_ERROR:
                    msgId = R.string.fw_get_in_vm_failed;
                    b.setPositiveButton(R.string.alert_dialog_yes, this);
                    b.setNegativeButton(R.string.alert_dialog_no, this);
                    break;
                default:
                    msgId = R.string.exception_error;
                    // Set Button 3, tells the activity that the error is
                    // not recoverable on dialog exit.
                    b.setNeutralButton(R.string.close_dialog, this);
                    break;
            }

            b.setTitle(getText(titleId));
            String message = getText(msgId).toString();
            b.setMessage(message);
            b.setCancelable(false);
            AlertDialog dialog = b.create();

            // make the dialog more obvious by bluring the background.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

            return dialog;
        } else if (id == VOICEMAIL_FWD_SAVING_DIALOG || id == VOICEMAIL_FWD_READING_DIALOG ||
                id == VOICEMAIL_REVERTING_DIALOG) {
            ProgressDialog dialog = new ProgressDialog(this);
            dialog.setTitle(getText(R.string.updating_title));
            dialog.setIndeterminate(true);
            dialog.setCancelable(false);
            dialog.setMessage(getText(
                    id == VOICEMAIL_FWD_SAVING_DIALOG ? R.string.updating_settings :
                            (id == VOICEMAIL_REVERTING_DIALOG ? R.string.reverting_settings :
                                    R.string.reading_settings)));
            return dialog;
        } else if (id == TTY_SET_RESPONSE_ERROR) {

            AlertDialog.Builder b = new AlertDialog.Builder(this);

            b.setTitle(getText(R.string.tty_mode_option_title));
            b.setMessage(getText(R.string.tty_mode_not_allowed_vt_call));
            b.setIconAttribute(android.R.attr.alertDialogIcon);
            b.setPositiveButton(R.string.ok, this);
            b.setCancelable(false);
            AlertDialog dialog = b.create();

            // make the dialog more obvious by bluring the background.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

            return dialog;
        }

        return null;
    }

    // This is a method implemented for DialogInterface.OnClickListener.
    // Used with the error dialog to close the app, voicemail dialog to just dismiss.
    // Close button is mapped to BUTTON_POSITIVE for the errors that close the activity,
    // while those that are mapped to BUTTON_NEUTRAL only move the preference focus.
    public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();
        switch (which){
            case DialogInterface.BUTTON_NEUTRAL:
                log("Neutral button");
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                log("Negative button");
                if (mCurrentDialogId == FW_GET_RESPONSE_ERROR) {
                    // We failed to get current forwarding settings and the user
                    // does not wish to continue.
                    switchToPreviousVoicemailProvider();
                }
                break;
            case DialogInterface.BUTTON_POSITIVE:
                log("Positive button");
                if (mCurrentDialogId == FW_GET_RESPONSE_ERROR) {
                    // We failed to get current forwarding settings but the user
                    // wishes to continue changing settings to the new vm provider
                    saveVoiceMailAndForwardingNumberStage2();
                } else {
                    finish();
                }
                return;
            default:
                // just let the dialog close and go back to the input
        }
        // In all dialogs, all buttons except BUTTON_POSITIVE lead to the end of user interaction
        // with settings UI. If we were called to explicitly configure voice mail then
        // we finish the settings activity here to come back to whatever the user was doing.
        if (getIntent().getAction().equals(ACTION_ADD_VOICEMAIL)) {
            finish();
        }
    }

    // set the app state with optional status.
    private void showVMDialog(int msgStatus) {
        switch (msgStatus) {
            // It's a bit worrisome to punt in the error cases here when we're
            // not in the foreground; maybe toast instead?
            case MSG_VM_EXCEPTION:
                showDialogIfForeground(VM_RESPONSE_ERROR);
                break;
            case MSG_FW_SET_EXCEPTION:
                showDialogIfForeground(FW_SET_RESPONSE_ERROR);
                break;
            case MSG_FW_GET_EXCEPTION:
                showDialogIfForeground(FW_GET_RESPONSE_ERROR);
                break;
            case MSG_VM_NOCHANGE:
                showDialogIfForeground(VM_NOCHANGE_ERROR);
                break;
            case MSG_VM_OK:
                showDialogIfForeground(VOICEMAIL_DIALOG_CONFIRM);
                break;
            case MSG_OK:
            default:
                // This should never happen.
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        log("onCreate(). Intent: " + getIntent());
        mPhone = getPhone();
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        addPreferencesFromResource(getPreferencesResource());

        // get buttons
        PreferenceScreen prefSet = getPreferenceScreen();

        onCreateVoicemailPrefs(icicle);
        onCreateRingtonePrefs(prefSet);

        mPlayDtmfTone = (CheckBoxPreference) findPreference(BUTTON_PLAY_DTMF_TONE);

        mButtonDTMF = (ListPreference) findPreference(BUTTON_DTMF_KEY);
        mButtonAutoRetry = (CheckBoxPreference) findPreference(BUTTON_RETRY_KEY);
        mButtonHAC = (CheckBoxPreference) findPreference(BUTTON_HAC_KEY);
        mButtonTTY = (ListPreference) findPreference(BUTTON_TTY_KEY);
        mButtonNoiseSuppression = (CheckBoxPreference) findPreference(BUTTON_NOISE_SUPPRESSION_KEY);

        mButtonBlacklist = (PreferenceScreen) findPreference(BUTTON_BLACKLIST);
        mT9SearchInputLocale = (ListPreference) findPreference(BUTTON_T9_SEARCH_INPUT_LOCALE);
        mIncomingCallStyle = (ListPreference) findPreference(BUTTON_INCOMING_CALL_STYLE);
        mButtonProximity = (CheckBoxPreference) findPreference(BUTTON_PROXIMITY_KEY);
        mIPPrefix = (PreferenceScreen) findPreference(BUTTON_IPPREFIX_KEY);

        mCallRecordingFormat = (ListPreference) findPreference(CALL_RECORDING_FORMAT);

        if (mT9SearchInputLocale != null) {
            initT9SearchInputPreferenceList();
        }

        if (mIPPrefix != null) {
            String ip_prefix = Settings.System.getString(getContentResolver(),
                    Constants.SETTINGS_IP_PREFIX + 1);
            if (TextUtils.isEmpty(ip_prefix)) {
                mIPPrefix.setSummary(R.string.ipcall_sub_summery);
            } else {
                mIPPrefix.setSummary(ip_prefix);
            }
        }

        final ContentResolver contentResolver = getContentResolver();

        mEmergencyCall = (PreferenceScreen) findPreference(BUTTON_EMERGENCY_CALL_KEY);

        if (mPlayDtmfTone != null) {
            mPlayDtmfTone.setChecked(Settings.System.getInt(contentResolver,
                    Settings.System.DTMF_TONE_WHEN_DIALING, 1) != 0);
        }

        if (mButtonDTMF != null) {
            if (getResources().getBoolean(R.bool.dtmf_type_enabled)) {
                mButtonDTMF.setOnPreferenceChangeListener(this);
            }
        }

        if (mButtonProximity != null) {
            mButtonProximity.setOnPreferenceChangeListener(this);
        }

        if (mButtonAutoRetry != null) {
            if (getResources().getBoolean(R.bool.auto_retry_enabled)) {
                mButtonAutoRetry.setOnPreferenceChangeListener(this);
            }
        }

        if (mButtonHAC != null) {
            if (getResources().getBoolean(R.bool.hac_enabled)) {
                mButtonHAC.setOnPreferenceChangeListener(this);
            }
        }

        if (mButtonTTY != null) {
            if (getResources().getBoolean(R.bool.tty_enabled)) {
                mButtonTTY.setOnPreferenceChangeListener(this);
            }
        }

        if (mButtonNoiseSuppression != null) {
            if (getResources().getBoolean(R.bool.has_in_call_noise_suppression)) {
                mButtonNoiseSuppression.setOnPreferenceChangeListener(this);
            }
        }

        if (mT9SearchInputLocale != null) {
            // should this be enabled/disabled based on a flag?
            mT9SearchInputLocale.setOnPreferenceChangeListener(this);
        }

        if (mCallRecordingFormat != null) {
            int format = Settings.System.getInt(getContentResolver(), Settings.System.CALL_RECORDING_FORMAT, 0);
            mCallRecordingFormat.setValue(String.valueOf(format));
            mCallRecordingFormat.setSummary(mCallRecordingFormat.getEntry());
            mCallRecordingFormat.setOnPreferenceChangeListener(this);
        }

        removeOptionalPrefs(prefSet);
        addOptionalPrefs(prefSet);

        onCreateLookupPrefs();

        // create intent to bring up contact list
        mContactListIntent = new Intent(Intent.ACTION_GET_CONTENT);
        mContactListIntent.setType(android.provider.Contacts.Phones.CONTENT_ITEM_TYPE);

        createSipCallSettings();
        createImsSettings();

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayShowHomeEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
        }
    }

    private void updateRingtoneName(int type, Preference preference, int msg) {
        if (preference == null) return;
        final Uri ringtoneUri;
        boolean defaultRingtone = false;
        if (type == RingtoneManager.TYPE_NOTIFICATION) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mPhone.getContext());
            // for voicemail notifications, we use the value saved in Phone's shared preferences.
            String uriString = prefs.getString(preference.getKey(), null);
            if (TextUtils.isEmpty(uriString)) {
                // silent ringtone
                ringtoneUri = null;
            } else {
                if (uriString.equals(Settings.System.DEFAULT_NOTIFICATION_URI.toString())) {
                    // If it turns out that the voicemail notification is set to the system
                    // default notification, we retrieve the actual URI to prevent it from showing
                    // up as "Unknown Ringtone".
                    defaultRingtone = true;
                    ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(this, type);
                } else {
                    ringtoneUri = Uri.parse(uriString);
                }
            }
        } else {
            // For ringtones, we can just lookup the system default because changing the settings
            // in Call Settings changes the system default.
            if (mSubscription == -1) {
                ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(this, type);
            } else {
                ringtoneUri = RingtoneManager.getActualRingtoneUriBySubId(this, mSubscription);
            }
        }

        CharSequence summary = getString(com.android.internal.R.string.ringtone_unknown);
        // Is it a silent ringtone?
        if (ringtoneUri == null) {
            summary = getString(com.android.internal.R.string.ringtone_silent);
        } else {
            // Fetch the ringtone title from the media provider
            try {
                Cursor cursor = getContentResolver().query(ringtoneUri,
                        new String[] { MediaStore.Audio.Media.TITLE }, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        summary = cursor.getString(0);
                    }
                    cursor.close();
                }
            } catch (SQLiteException sqle) {
                // Unknown title for the ringtone
            }
        }
        if (defaultRingtone) {
            summary = mPhone.getContext().getString(
                    R.string.default_notification_description, summary);
        }
        mRingtoneLookupComplete.sendMessage(mRingtoneLookupComplete.obtainMessage(msg, summary));
    }

    protected void createSipCallSettings() {
        // Add Internet call settings.
        if (PhoneUtils.isVoipSupported(this)) {
            mSipManager = SipManager.newInstance(this);
            mSipSharedPreferences = new SipSharedPreferences(this);
            addPreferencesFromResource(R.xml.sip_settings_category);
            mButtonSipCallOptions = getSipCallOptionPreference();
            mButtonSipCallOptions.setOnPreferenceChangeListener(this);
            mButtonSipCallOptions.setValueIndex(
                    mButtonSipCallOptions.findIndexOfValue(
                            mSipSharedPreferences.getSipCallOption()));
            mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
        }
    }

    protected void createImsSettings() {
        addPreferencesFromResource(R.xml.ims_settings_category);
    }

    // Gets the call options for SIP depending on whether SIP is allowed only
    // on Wi-Fi only; also make the other options preference invisible.
    private ListPreference getSipCallOptionPreference() {
        ListPreference wifiAnd3G = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS);
        ListPreference wifiOnly = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY);
        PreferenceGroup sipSettings = (PreferenceGroup)
                findPreference(SIP_SETTINGS_CATEGORY_KEY);
        if (SipManager.isSipWifiOnly(this)) {
            sipSettings.removePreference(wifiAnd3G);
            return wifiOnly;
        } else {
            sipSettings.removePreference(wifiOnly);
            return wifiAnd3G;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mForeground = true;

        if (isAirplaneModeOn()) {
            disablePreferencesForAirplaneMode(getPreferenceScreen());
        }

        onResumeVoicemailPrefs();
        onResumeRingtonePrefs();

        if (mButtonDTMF != null) {
            int dtmf = Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, Constants.DTMF_TONE_TYPE_NORMAL);
            mButtonDTMF.setValueIndex(dtmf);
        }

        if (mIncomingCallStyle != null) {
            int style = Settings.System.getInt(getContentResolver(),
                    Settings.System.INCOMING_CALL_STYLE,
                    Settings.System.INCOMING_CALL_STYLE_FULLSCREEN_PHOTO);
            mIncomingCallStyle.setOnPreferenceChangeListener(this);
            mIncomingCallStyle.setValueIndex(style);
        }

        if (mButtonAutoRetry != null) {
            int autoretry = Settings.Global.getInt(getContentResolver(),
                    Settings.Global.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        }

        if (mButtonHAC != null) {
            int hac = Settings.System.getInt(getContentResolver(), Settings.System.HEARING_AID, 0);
            mButtonHAC.setChecked(hac != 0);
        }

        if (mButtonTTY != null) {
            int settingsTtyMode = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
            mButtonTTY.setValue(Integer.toString(settingsTtyMode));
            updatePreferredTtyModeSummary(settingsTtyMode);
        }


        if (mButtonProximity != null) {
            boolean checked = Settings.System.getInt(getContentResolver(),
                    Constants.SETTINGS_PROXIMITY_SENSOR, 1) == 1;
            mButtonProximity.setChecked(checked);
            mButtonProximity.setSummary(checked ? R.string.proximity_on_summary
                    : R.string.proximity_off_summary);
        }

        updateBlacklistSummary();

        onResumeLookupPrefs();
    }

    private void disablePreferencesForAirplaneMode(PreferenceGroup group) {
        int count = group.getPreferenceCount();
        for (int i = 0 ; i < count ; ++i) {
            Preference pref = group.getPreference(i);
            if (DISABLE_IN_AIRPLANE_MODE_PREFS.contains(pref.getKey())) {
                pref.setEnabled(false);
            } else if (pref instanceof PreferenceGroup) {
                disablePreferencesForAirplaneMode((PreferenceGroup) pref);
            }
        }
    }

    private void updateBlacklistSummary() {
        if (mButtonBlacklist != null) {
            if (BlacklistUtils.isBlacklistEnabled(this)) {
                mButtonBlacklist.setSummary(R.string.blacklist_summary);
            } else {
                mButtonBlacklist.setSummary(R.string.blacklist_summary_disabled);
            }
        }
    }

    public static boolean migrateVoicemailVibrationSettingsIfNeeded(SharedPreferences prefs) {
        return migrateVoicemailVibrationSettingsIfNeeded(prefs, -1);
    }

    // Migrate settings from BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_WHEN_KEY to
    // BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_KEY, if the latter does not exist.
    // Returns true if migration was performed.
    public static boolean migrateVoicemailVibrationSettingsIfNeeded(SharedPreferences prefs,
            int subscripton) {
        String vibrateKey =
                PhoneSettings.getKeyForSubscription(BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_KEY,
                                subscripton);
        String vibrateWhenKey =
                PhoneSettings.getKeyForSubscription(BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_WHEN_KEY,
                        subscripton);
        if (!prefs.contains(vibrateKey)) {
            String vibrateWhen = prefs.getString(
                    vibrateWhenKey, VOICEMAIL_VIBRATION_NEVER);
            // If vibrateWhen is always, then voicemailVibrate should be True.
            // otherwise if vibrateWhen is "only in silent mode", or "never", then
            // voicemailVibrate = False.
            boolean voicemailVibrate = vibrateWhen.equals(VOICEMAIL_VIBRATION_ALWAYS);
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(vibrateKey, voicemailVibrate);
            editor.commit();
            return true;
        }
        return false;
    }

    /**
     * Obtain the setting for "vibrate when ringing" setting.
     *
     * Watch out: if the setting is missing in the device, this will try obtaining the old
     * "vibrate on ring" setting from AudioManager, and save the previous setting to the new one.
     */
    public static boolean getVibrateWhenRinging(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
    }

    /**
     * Lookups ringtone name asynchronously and updates the relevant Preference.
     */
    private void lookupRingtoneName() {
        new Thread(mRingtoneLookupRunnable).start();
    }

    private boolean isAirplaneModeOn() {
        return Settings.Global.getInt(getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    private void saveT9SearchInputLocale(Preference preference, String newT9Locale) {
        log("saveT9SearchInputLocale: requesting set t9 locale to " + newT9Locale);

        String lastT9Locale = Settings.System.getString(getContentResolver(),
                Settings.System.T9_SEARCH_INPUT_LOCALE);

        if (!TextUtils.equals(lastT9Locale, newT9Locale)) {
            Settings.System.putString(getContentResolver(),
                    Settings.System.T9_SEARCH_INPUT_LOCALE, newT9Locale);
        }
    }

    private void handleTTYChange(Preference preference, Object objValue) {
        int buttonTtyMode;
        buttonTtyMode = Integer.valueOf((String) objValue).intValue();
        int settingsTtyMode = android.provider.Settings.Secure.getInt(
                getContentResolver(),
                android.provider.Settings.Secure.PREFERRED_TTY_MODE, preferredTtyMode);
        log("handleTTYChange: requesting set TTY mode enable (TTY) to" +
                Integer.toString(buttonTtyMode));

        if (buttonTtyMode != settingsTtyMode) {
            switch(buttonTtyMode) {
                case Phone.TTY_MODE_OFF:
                case Phone.TTY_MODE_FULL:
                case Phone.TTY_MODE_HCO:
                case Phone.TTY_MODE_VCO:
                    android.provider.Settings.Secure.putInt(getContentResolver(),
                            android.provider.Settings.Secure.PREFERRED_TTY_MODE, buttonTtyMode);
                    break;
                default:
                    buttonTtyMode = Phone.TTY_MODE_OFF;
            }

            mButtonTTY.setValue(Integer.toString(buttonTtyMode));
            updatePreferredTtyModeSummary(buttonTtyMode);
            Intent ttyModeChanged = new Intent(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            ttyModeChanged.putExtra(TtyIntent.TTY_PREFFERED_MODE, buttonTtyMode);
            sendBroadcastAsUser(ttyModeChanged, UserHandle.ALL);
        }
    }

    private void handleSipCallOptionsChange(Object objValue) {
        String option = objValue.toString();
        mSipSharedPreferences.setSipCallOption(option);
        mButtonSipCallOptions.setValueIndex(
                mButtonSipCallOptions.findIndexOfValue(option));
        mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
    }

    private void updatePreferredTtyModeSummary(int TtyMode) {
        String [] txts = getResources().getStringArray(R.array.tty_mode_entries);
        switch(TtyMode) {
            case Phone.TTY_MODE_OFF:
            case Phone.TTY_MODE_HCO:
            case Phone.TTY_MODE_VCO:
            case Phone.TTY_MODE_FULL:
                mButtonTTY.setSummary(txts[TtyMode]);
                break;
            default:
                mButtonTTY.setEnabled(false);
                mButtonTTY.setSummary(txts[Phone.TTY_MODE_OFF]);
        }
    }

    /**
     * Updates the look of the VM preference widgets based on current VM provider settings.
     * Note that the provider name is loaded form the found activity via loadLabel in
     * {@link #initVoiceMailProviders()} in order for it to be localizable.
     */
    private void updateVMPreferenceWidgets(String currentProviderSetting) {
        final String key = currentProviderSetting;
        final VoiceMailProvider provider = mVMProvidersData.get(key);

        /* This is the case when we are coming up on a freshly wiped phone and there is no
         persisted value for the list preference mVoicemailProviders.
         In this case we want to show the UI asking the user to select a voicemail provider as
         opposed to silently falling back to default one. */
        if (provider == null) {
            log("updateVMPreferenceWidget: provider for the key \"" + key + "\" is null.");
            mVoicemailProviders.setSummary(getString(R.string.sum_voicemail_choose_provider));
            mVoicemailSettings.setEnabled(false);
            mVoicemailSettings.setIntent(null);

            mVoicemailNotificationVibrate.setEnabled(false);
        } else {
            log("updateVMPreferenceWidget: provider for the key \"" + key + "\".."
                    + "name: " + provider.name
                    + ", intent: " + provider.intent);
            final String providerName = provider.name;
            mVoicemailProviders.setSummary(providerName);
            mVoicemailSettings.setEnabled(true);
            mVoicemailSettings.setIntent(provider.intent);
            mVoicemailNotificationVibrate.setEnabled(true);
        }
    }

    private void saveLookupProviderSwitch(Preference pref, Boolean newValue) {
        log("saveLookupProviderSwitch()");

        String key;

        if (pref == mEnableForwardLookup) {
            key = Settings.System.ENABLE_FORWARD_LOOKUP;
        } else if (pref == mEnablePeopleLookup) {
            key = Settings.System.ENABLE_PEOPLE_LOOKUP;
        } else if (pref == mEnableReverseLookup) {
            key = Settings.System.ENABLE_REVERSE_LOOKUP;
        } else {
            return;
        }

        Settings.System.putInt(getContentResolver(), key, newValue ? 1 : 0);
    }

    private void restoreLookupProviderSwitches() {
        log("restoreLookupProviderSwitches()");

        mEnableForwardLookup.setChecked(Settings.System.getInt(
                getContentResolver(),
                Settings.System.ENABLE_FORWARD_LOOKUP, 1) != 0);
        mEnablePeopleLookup.setChecked(Settings.System.getInt(
                getContentResolver(),
                Settings.System.ENABLE_PEOPLE_LOOKUP, 1) != 0);
        mEnableReverseLookup.setChecked(Settings.System.getInt(
                getContentResolver(),
                Settings.System.ENABLE_REVERSE_LOOKUP, 1) != 0);
    }

    private void restoreLookupProvider(ListPreference pref, String key) {
        String provider = Settings.System.getString(getContentResolver(), key);
        if (provider == null) {
            pref.setValueIndex(0);
            saveLookupProviderSetting(pref, pref.getEntryValues()[0].toString());
        } else {
            pref.setValue(provider);
        }
    }

    private void restoreLookupProviders() {
        log("restoreLookupProviders()");

        restoreLookupProvider(mChooseForwardLookupProvider,
                Settings.System.FORWARD_LOOKUP_PROVIDER);
        restoreLookupProvider(mChoosePeopleLookupProvider,
                Settings.System.PEOPLE_LOOKUP_PROVIDER);
        restoreLookupProvider(mChooseReverseLookupProvider,
                Settings.System.REVERSE_LOOKUP_PROVIDER);
    }

    private void saveLookupProviderSetting(Preference pref, String newValue) {
        log("saveLookupProviderSetting()");

        String key;

        if (pref == mChooseForwardLookupProvider) {
            key = Settings.System.FORWARD_LOOKUP_PROVIDER;
        } else if (pref == mChoosePeopleLookupProvider) {
            key = Settings.System.PEOPLE_LOOKUP_PROVIDER;
        } else if (pref == mChooseReverseLookupProvider) {
            key = Settings.System.REVERSE_LOOKUP_PROVIDER;
        } else {
            return;
        }

        Settings.System.putString(getContentResolver(), key, newValue);
    }

    /**
     * Enumerates existing VM providers and puts their data into the list and populates
     * the preference list objects with their names.
     * In case we are called with ACTION_ADD_VOICEMAIL intent the intent may have
     * an extra string called IGNORE_PROVIDER_EXTRA with "package.activityName" of the provider
     * which should be hidden when we bring up the list of possible VM providers to choose.
     */
    private void initVoiceMailProviders() {
        log("initVoiceMailProviders()");
        mPerProviderSavedVMNumbers =
                this.getApplicationContext().getSharedPreferences(
                        mSubscription == -1 ?
                                VM_NUMBERS_SHARED_PREFERENCES_NAME :
                                VM_NUMBERS_SHARED_PREFERENCES_NAME + mSubscription,
                        MODE_PRIVATE);

        String providerToIgnore = null;
        if (getIntent().getAction().equals(ACTION_ADD_VOICEMAIL)) {
            if (getIntent().hasExtra(IGNORE_PROVIDER_EXTRA)) {
                providerToIgnore = getIntent().getStringExtra(IGNORE_PROVIDER_EXTRA);
            }
            log("Found ACTION_ADD_VOICEMAIL. providerToIgnore=" + providerToIgnore);
            if (providerToIgnore != null) {
                // IGNORE_PROVIDER_EXTRA implies we want to remove the choice from the list.
                deleteSettingsForVoicemailProvider(providerToIgnore);
            }
        }

        mVMProvidersData.clear();

        // Stick the default element which is always there
        final String myCarrier = getString(R.string.voicemail_default);
        mVMProvidersData.put(DEFAULT_VM_PROVIDER_KEY, new VoiceMailProvider(myCarrier, null));

        // Enumerate providers
        PackageManager pm = getPackageManager();
        Intent intent = new Intent();
        intent.setAction(ACTION_CONFIGURE_VOICEMAIL);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        int len = resolveInfos.size() + 1; // +1 for the default choice we will insert.

        // Go through the list of discovered providers populating the data map
        // skip the provider we were instructed to ignore if there was one
        for (int i = 0; i < resolveInfos.size(); i++) {
            final ResolveInfo ri= resolveInfos.get(i);
            final ActivityInfo currentActivityInfo = ri.activityInfo;
            final String key = makeKeyForActivity(currentActivityInfo);
            if (key.equals(providerToIgnore)) {
                log("Ignoring key: " + key);
                len--;
                continue;
            }
            log("Loading key: " + key);
            final String nameForDisplay = ri.loadLabel(pm).toString();
            Intent providerIntent = new Intent();
            providerIntent.setAction(ACTION_CONFIGURE_VOICEMAIL);
            providerIntent.setClassName(currentActivityInfo.packageName,
                    currentActivityInfo.name);
            log("Store loaded VoiceMailProvider. key: " + key
                    + " -> name: " + nameForDisplay + ", intent: " + providerIntent);
            mVMProvidersData.put(
                    key,
                    new VoiceMailProvider(nameForDisplay, providerIntent));

        }

        // Now we know which providers to display - create entries and values array for
        // the list preference
        String [] entries = new String [len];
        String [] values = new String [len];
        entries[0] = myCarrier;
        values[0] = DEFAULT_VM_PROVIDER_KEY;
        int entryIdx = 1;
        for (int i = 0; i < resolveInfos.size(); i++) {
            final String key = makeKeyForActivity(resolveInfos.get(i).activityInfo);
            if (!mVMProvidersData.containsKey(key)) {
                continue;
            }
            entries[entryIdx] = mVMProvidersData.get(key).name;
            values[entryIdx] = key;
            entryIdx++;
        }

        // ListPreference is now updated.
        mVoicemailProviders.setEntries(entries);
        mVoicemailProviders.setEntryValues(values);

        // Remember the current Voicemail Provider key as a "previous" key. This will be used
        // when we fail to update Voicemail Provider, which requires rollback.
        // We will update this when the VM Provider setting is successfully updated.
        mPreviousVMProviderKey = getCurrentVoicemailProviderKey();
        log("Set up the first mPreviousVMProviderKey: " + mPreviousVMProviderKey);

        // Finally update the preference texts.
        updateVMPreferenceWidgets(mPreviousVMProviderKey);
    }

    private void initT9SearchInputPreferenceList() {
        int len = T9_SEARCH_INPUT_LOCALES.length + 1;
        String[] entries = new String[len];
        String[] values = new String[len];

        entries[0] = getString(R.string.t9_search_input_locale_default);
        values[0] = "";

        // add locales programatically so we can use locale.getDisplayName
        for (int i = 0; i < T9_SEARCH_INPUT_LOCALES.length; i++) {
            Locale locale = T9_SEARCH_INPUT_LOCALES[i];
            entries[i + 1] = locale.getDisplayName();
            values[i + 1] = locale.toString();
        }

        // Set current entry from global system setting
        String settingsT9Locale = Settings.System.getString(getContentResolver(),
                Settings.System.T9_SEARCH_INPUT_LOCALE);
        if (settingsT9Locale != null) {
            mT9SearchInputLocale.setValue(settingsT9Locale);
        }

        mT9SearchInputLocale.setEntries(entries);
        mT9SearchInputLocale.setEntryValues(values);
    }

    private String makeKeyForActivity(ActivityInfo ai) {
        return ai.name;
    }

    /**
     * Simulates user clicking on a passed preference.
     * Usually needed when the preference is a dialog preference and we want to invoke
     * a dialog for this preference programmatically.
     * TODO(iliat): figure out if there is a cleaner way to cause preference dlg to come up
     */
    private void simulatePreferenceClick(Preference preference) {
        // Go through settings until we find our setting
        // and then simulate a click on it to bring up the dialog
        final ListAdapter adapter = getPreferenceScreen().getRootAdapter();
        for (int idx = 0; idx < adapter.getCount(); idx++) {
            if (adapter.getItem(idx) == preference) {
                getPreferenceScreen().onItemClick(this.getListView(),
                        null, idx, adapter.getItemId(idx));
                break;
            }
        }
    }

    /**
     * Saves new VM provider settings associating them with the currently selected
     * provider if settings are different than the ones already stored for this
     * provider.
     * Later on these will be used when the user switches a provider.
     */
    private void maybeSaveSettingsForVoicemailProvider(String key,
            VoiceMailProviderSettings newSettings) {
        if (mVoicemailProviders == null) {
            return;
        }
        final VoiceMailProviderSettings curSettings = loadSettingsForVoiceMailProvider(key);
        if (newSettings.equals(curSettings)) {
            if (DBG) {
                log("maybeSaveSettingsForVoicemailProvider:"
                        + " Not saving setting for " + key + " since they have not changed");
            }
            return;
        }
        log("Saving settings for " + key + ": " + newSettings.toString());
        Editor editor = mPerProviderSavedVMNumbers.edit();
        editor.putString(key + VM_NUMBER_TAG, newSettings.voicemailNumber);
        String fwdKey = key + FWD_SETTINGS_TAG;
        CallForwardInfo[] s = newSettings.forwardingSettings;
        if (s != FWD_SETTINGS_DONT_TOUCH) {
            editor.putInt(fwdKey + FWD_SETTINGS_LENGTH_TAG, s.length);
            for (int i = 0; i < s.length; i++) {
                final String settingKey = fwdKey + FWD_SETTING_TAG + String.valueOf(i);
                final CallForwardInfo fi = s[i];
                editor.putInt(settingKey + FWD_SETTING_STATUS, fi.status);
                editor.putInt(settingKey + FWD_SETTING_REASON, fi.reason);
                editor.putString(settingKey + FWD_SETTING_NUMBER, fi.number);
                editor.putInt(settingKey + FWD_SETTING_TIME, fi.timeSeconds);
            }
        } else {
            editor.putInt(fwdKey + FWD_SETTINGS_LENGTH_TAG, 0);
        }
        editor.apply();
    }

    /**
     * Returns settings previously stored for the currently selected
     * voice mail provider. If none is stored returns null.
     * If the user switches to a voice mail provider and we have settings
     * stored for it we will automatically change the phone's voice mail number
     * and forwarding number to the stored one. Otherwise we will bring up provider's configuration
     * UI.
     */
    private VoiceMailProviderSettings loadSettingsForVoiceMailProvider(String key) {
        final String vmNumberSetting = mPerProviderSavedVMNumbers.getString(key + VM_NUMBER_TAG,
                null);
        if (vmNumberSetting == null) {
            Log.w(LOG_TAG, "VoiceMailProvider settings for the key \"" + key + "\""
                    + " was not found. Returning null.");
            return null;
        }

        CallForwardInfo[] cfi = FWD_SETTINGS_DONT_TOUCH;
        String fwdKey = key + FWD_SETTINGS_TAG;
        final int fwdLen = mPerProviderSavedVMNumbers.getInt(fwdKey + FWD_SETTINGS_LENGTH_TAG, 0);
        if (fwdLen > 0) {
            cfi = new CallForwardInfo[fwdLen];
            for (int i = 0; i < cfi.length; i++) {
                final String settingKey = fwdKey + FWD_SETTING_TAG + String.valueOf(i);
                cfi[i] = new CallForwardInfo();
                cfi[i].status = mPerProviderSavedVMNumbers.getInt(
                        settingKey + FWD_SETTING_STATUS, 0);
                cfi[i].reason = mPerProviderSavedVMNumbers.getInt(
                        settingKey + FWD_SETTING_REASON,
                        CommandsInterface.CF_REASON_ALL_CONDITIONAL);
                cfi[i].serviceClass = CommandsInterface.SERVICE_CLASS_VOICE;
                cfi[i].toa = PhoneNumberUtils.TOA_International;
                cfi[i].number = mPerProviderSavedVMNumbers.getString(
                        settingKey + FWD_SETTING_NUMBER, "");
                cfi[i].timeSeconds = mPerProviderSavedVMNumbers.getInt(
                        settingKey + FWD_SETTING_TIME, 20);
            }
        }

        VoiceMailProviderSettings settings =  new VoiceMailProviderSettings(vmNumberSetting, cfi);
        log("Loaded settings for " + key + ": " + settings.toString());
        return settings;
    }

    /**
     * Deletes settings for the specified provider.
     */
    private void deleteSettingsForVoicemailProvider(String key) {
        log("Deleting settings for" + key);
        if (mVoicemailProviders == null) {
            return;
        }
        mPerProviderSavedVMNumbers.edit()
                .putString(key + VM_NUMBER_TAG, null)
                .putInt(key + FWD_SETTINGS_TAG + FWD_SETTINGS_LENGTH_TAG, 0)
                .commit();
    }

    private String getCurrentVoicemailProviderKey() {
        final String key = mVoicemailProviders.getValue();
        return (key != null) ? key : DEFAULT_VM_PROVIDER_KEY;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void onCreateVoicemailPrefs(Bundle savedInstanceState) {
        mVoicemailCategory = (PreferenceScreen)findPreference(BUTTON_VOICEMAIL_CATEGORY_KEY);
        mSubMenuVoicemailSettings =
                (EditPhoneNumberPreference) findPreference(BUTTON_VOICEMAIL_KEY);
        if (mSubMenuVoicemailSettings != null) {
            mSubMenuVoicemailSettings.setParentActivity(this, VOICEMAIL_PREF_ID, this);
            mSubMenuVoicemailSettings.setDialogOnClosedListener(this);
            mSubMenuVoicemailSettings.setDialogTitle(R.string.voicemail_settings_number_label);
        }
        mMwiNotification = (CheckBoxPreference) findPreference(BUTTON_MWI_NOTIFICATION_KEY);
        if (mMwiNotification != null) {
            if (getResources().getBoolean(R.bool.sprint_mwi_quirk)) {
                mMwiNotification.setOnPreferenceChangeListener(this);
            } else {
                PreferenceGroup voicemailCategory =
                        (PreferenceGroup) findPreference(BUTTON_VOICEMAIL_CATEGORY_KEY);
                voicemailCategory.removePreference(mMwiNotification);
                mMwiNotification = null;
            }
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                mPhone.getContext());
        mVoicemailProviders = (ListPreference) findPreference(BUTTON_VOICEMAIL_PROVIDER_KEY);
        if (mVoicemailProviders != null) {
            PhoneSettings.setPreferenceKeyForSubscription(mVoicemailProviders, mSubscription);
            mVoicemailProviders.setValue(prefs.getString(mVoicemailProviders.getKey(),
                    mVoicemailProviders.getValue()));
            mVoicemailProviders.setOnPreferenceChangeListener(this);
            mVoicemailSettings = (PreferenceScreen)findPreference(BUTTON_VOICEMAIL_SETTING_KEY);
            mVoicemailNotificationRingtone =
                    findPreference(BUTTON_VOICEMAIL_NOTIFICATION_RINGTONE_KEY);
            PhoneSettings.setPreferenceKeyForSubscription(mVoicemailNotificationRingtone, mSubscription);
            mVoicemailNotificationVibrate =
                    (CheckBoxPreference) findPreference(BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_KEY);
            PhoneSettings.setPreferenceKeyForSubscription(mVoicemailNotificationVibrate, mSubscription);
            initVoiceMailProviders();
        }
        // check the intent that started this activity and pop up the voicemail
        // dialog if we've been asked to.
        // If we have at least one non default VM provider registered then bring up
        // the selection for the VM provider, otherwise bring up a VM number dialog.
        // We only bring up the dialog the first time we are called (not after orientation change)
        if (savedInstanceState == null) {
            if (getIntent().getAction().equals(ACTION_ADD_VOICEMAIL) &&
                    mVoicemailProviders != null) {
                log("ACTION_ADD_VOICEMAIL Intent is thrown. current VM data size: "
                        + mVMProvidersData.size());
                if (mVMProvidersData.size() > 1) {
                    simulatePreferenceClick(mVoicemailProviders);
                } else {
                    onPreferenceChange(mVoicemailProviders, DEFAULT_VM_PROVIDER_KEY);
                    mVoicemailProviders.setValue(DEFAULT_VM_PROVIDER_KEY);
                }
            }
        }
        updateVoiceNumberField();
        mVMProviderSettingsForced = false;
    }

    protected void onResumeVoicemailPrefs() {
        if (mMwiNotification != null) {
            int mwiNotification = Settings.System.getInt(getContentResolver(),
                    Settings.System.ENABLE_MWI_NOTIFICATION, 0);
            mMwiNotification.setChecked(mwiNotification != 0);
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                mPhone.getContext());
        migrateVoicemailVibrationSettingsIfNeeded(prefs,mSubscription);
        mVoicemailNotificationVibrate.setChecked(prefs.getBoolean(
                PhoneSettings.getKeyForSubscription(BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_KEY,
                        mSubscription),
                false));
        lookupRingtoneName();
    }

    protected void onCreateRingtonePrefs(PreferenceScreen preferenceScreen) {
        mRingtonePreference = (DefaultRingtonePreference)findPreference(BUTTON_RINGTONE_KEY);
        if (mRingtonePreference != null && mSubscription != -1) {
            mRingtonePreference.setSubId(mSubscription);
        }
        mVibrateWhenRinging = (CheckBoxPreference) findPreference(BUTTON_VIBRATE_ON_RING);
        if (mVibrateWhenRinging != null) {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                mVibrateWhenRinging.setOnPreferenceChangeListener(this);
            } else {
                preferenceScreen.removePreference(mVibrateWhenRinging);
                mVibrateWhenRinging = null;
            }
        }
        mRingtoneLookupRunnable = new Runnable() {
            @Override
            public void run() {
                if (mRingtonePreference != null) {
                    updateRingtoneName(RingtoneManager.TYPE_RINGTONE, mRingtonePreference,
                            MSG_UPDATE_RINGTONE_SUMMARY);
                }
                if (mVoicemailNotificationRingtone != null) {
                    updateRingtoneName(RingtoneManager.TYPE_NOTIFICATION,
                            mVoicemailNotificationRingtone, MSG_UPDATE_VOICEMAIL_RINGTONE_SUMMARY);
                }
            }
        };
    }

    protected void onResumeRingtonePrefs() {
        if (mVibrateWhenRinging != null) {
            mVibrateWhenRinging.setChecked(getVibrateWhenRinging(this));
        }
        lookupRingtoneName();
    }

    protected void onCreateLookupPrefs() {
        mEnableForwardLookup = (CheckBoxPreference)
                findPreference(SWITCH_ENABLE_FORWARD_LOOKUP);
        mEnablePeopleLookup = (CheckBoxPreference)
                findPreference(SWITCH_ENABLE_PEOPLE_LOOKUP);
        mEnableReverseLookup = (CheckBoxPreference)
                findPreference(SWITCH_ENABLE_REVERSE_LOOKUP);

        mEnableForwardLookup.setOnPreferenceChangeListener(this);
        mEnablePeopleLookup.setOnPreferenceChangeListener(this);
        mEnableReverseLookup.setOnPreferenceChangeListener(this);

        restoreLookupProviderSwitches();

        mChooseForwardLookupProvider = (ListPreference)
                findPreference(BUTTON_CHOOSE_FORWARD_LOOKUP_PROVIDER);
        mChoosePeopleLookupProvider = (ListPreference)
                findPreference(BUTTON_CHOOSE_PEOPLE_LOOKUP_PROVIDER);
        mChooseReverseLookupProvider = (ListPreference)
                findPreference(BUTTON_CHOOSE_REVERSE_LOOKUP_PROVIDER);

        mChooseForwardLookupProvider.setOnPreferenceChangeListener(this);
        mChoosePeopleLookupProvider.setOnPreferenceChangeListener(this);
        mChooseReverseLookupProvider.setOnPreferenceChangeListener(this);

        String[] reverseLookupNames = getResources().getStringArray(
                R.array.reverse_lookup_provider_names);
        String[] reverseLookupProviders = getResources().getStringArray(
                R.array.reverse_lookup_providers);
        String cyngnProviderPackage = getString(R.string.cyngn_reverse_lookup_provider_package);

        if (PhoneUtils.isPackageInstalled(this, cyngnProviderPackage)) {
            reverseLookupNames = Arrays.copyOf(reverseLookupNames, reverseLookupNames.length + 1);
            reverseLookupProviders = Arrays.copyOf(reverseLookupProviders,
                    reverseLookupProviders.length + 1);

            reverseLookupNames[reverseLookupNames.length - 1] =
                    getString(R.string.cyngn_reverse_lookup_provider_name);
            reverseLookupProviders[reverseLookupProviders.length - 1] =
                    getString(R.string.cyngn_reverse_lookup_provider_value);
        }

        mChooseReverseLookupProvider.setEntries(reverseLookupNames);
        mChooseReverseLookupProvider.setEntryValues(reverseLookupProviders);

        restoreLookupProviders();
    }

    protected void onResumeLookupPrefs() {
        restoreLookupProviderSwitches();
        restoreLookupProviders();
    }

    protected int getPreferencesResource() {
        return R.xml.call_feature_setting;
    }

    protected Phone getPhone() {
        return PhoneGlobals.getPhone();
    }

    protected void removeOptionalPrefs(PreferenceScreen preferenceScreen) {
        if (!getResources().getBoolean(R.bool.dtmf_type_enabled) && mButtonDTMF != null) {
            preferenceScreen.removePreference(mButtonDTMF);
            mButtonDTMF = null;
        }
        if (!getResources().getBoolean(R.bool.show_emergency_call_list) && mEmergencyCall != null) {
            preferenceScreen.removePreference(mEmergencyCall);
        }
        if (!getResources().getBoolean(R.bool.auto_retry_enabled) && mButtonAutoRetry != null) {
            preferenceScreen.removePreference(mButtonAutoRetry);
            mButtonAutoRetry = null;
        }
        if (!getResources().getBoolean(R.bool.hac_enabled) && mButtonHAC != null) {
            preferenceScreen.removePreference(mButtonHAC);
            mButtonHAC = null;
        }
        if (!getResources().getBoolean(R.bool.tty_enabled) && mButtonTTY != null) {
            preferenceScreen.removePreference(mButtonTTY);
            mButtonTTY = null;
        }
        if (!getResources().getBoolean(R.bool.has_in_call_noise_suppression) &&
                mButtonNoiseSuppression != null) {
            preferenceScreen.removePreference(mButtonNoiseSuppression);
            mButtonNoiseSuppression = null;
        }
        if (!getResources().getBoolean(R.bool.config_proximity_enable) &&
                mButtonProximity != null) {
            preferenceScreen.removePreference(mButtonProximity);
            mButtonProximity = null;
        }
        if (!SystemProperties.getBoolean("persist.radio.ipcall.enabled", false) &&
                mIPPrefix != null) {
            preferenceScreen.removePreference(mIPPrefix);
            mIPPrefix = null;
        }
        if (!getResources().getBoolean(R.bool.world_phone)) {
            Preference options = preferenceScreen.findPreference(BUTTON_CDMA_OPTIONS);
            if (options != null)
                preferenceScreen.removePreference(options);
            options = preferenceScreen.findPreference(BUTTON_GSM_UMTS_OPTIONS);
            if (options != null)
                preferenceScreen.removePreference(options);

            int phoneType = mPhone.getPhoneType();
            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                Preference fdnButton = preferenceScreen.findPreference(BUTTON_FDN_KEY);
                if (fdnButton != null && getResources().getBoolean(R.bool.config_fdn_disable))
                    preferenceScreen.removePreference(fdnButton);
                if (!getResources().getBoolean(R.bool.config_voice_privacy_disable)) {
                    addPreferencesFromResource(R.xml.cdma_call_privacy);
                    PhoneGlobals.initCallWaitingPref(this, SUB1);
                }
            }
        }

        // Remove Call recording format preference if it's not enabled
        boolean recordingEnabled = false;
        try {
            PackageManager pm = getPackageManager();
            String phonePackage = "com.android.dialer";
            Resources res;
            res = pm.getResourcesForApplication(phonePackage);
            int booleanID = res.getIdentifier(phonePackage + ":bool/call_recording_enabled", null, null);
            recordingEnabled = res.getBoolean(booleanID);
        } catch (NameNotFoundException ex) {
            ex.printStackTrace();
        } catch (NotFoundException ex) {
            ex.printStackTrace();
        } finally {
            if (!recordingEnabled && mCallRecordingFormat != null) {
                preferenceScreen.removePreference(mCallRecordingFormat);
            }
        }
    }

    protected void addOptionalPrefs(PreferenceScreen preferenceScreen) {
        if (!getResources().getBoolean(R.bool.world_phone)) {
            int phoneType = mPhone.getPhoneType();
            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                if (!getResources().getBoolean(R.bool.config_voice_privacy_disable)) {
                    addPreferencesFromResource(R.xml.cdma_call_privacy);
                }
            } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                addPreferencesFromResource(R.xml.gsm_umts_call_options);
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
        }
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(Activity activity) {
         if (!MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            // HACK: This doesn't work on multi-SIM (and we have multiple)
            //       hierachy (MSimCallFeaturesSetting and MSimCallFeatureSubSetting)
            //       so let just allow this to act like "back" button
            Intent intent = new Intent(activity, CallFeaturesSetting.class);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(intent);
        }
        activity.finish();
    }

    private static void log(String msg) {
        if (DBG)  Log.d(LOG_TAG, msg);
    }
}