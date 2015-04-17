/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.activity.setup;

import android.app.ActionBar;
import android.app.Activity;
import android.app.LoaderManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.android.mail.preferences.FolderPreferences;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;
import com.google.common.base.Preconditions;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Mailbox settings" activity.
 *
 * It's used to update per-mailbox sync settings.  It normally updates Mailbox settings, unless
 * the target mailbox is Inbox, in which case it updates Account settings instead.
 *
 * All changes made by the user will not be immediately saved to the database, as changing the
 * sync window may result in removal of messages.  Instead, we only save to the database in {@link
 * #onDestroy()}, unless it's called for configuration changes.
 */
public class MailboxSettings extends PreferenceActivity {
    public static final String PREFERENCE_SYNC_SETTINGS = "account_sync_settings";
    public static final String PREFERENCE_PER_FOLDER_NOTIFICATIONS =
            "account_per_folder_notifications";

    private static final String EXTRA_FOLDERS_URI = "FOLDERS_URI";
    private static final String EXTRA_INBOX_ID = "INBOX_ID";
    private static final String EXTRA_TYPE = "TYPE";

    private static final String EXTRA_HEADER_FOLDER_INDENT = "folder-indent";

    private static final int FOLDERS_LOADER_ID = 0;
    private Uri mFoldersUri;
    private int mInboxId;
    private String mType;
    private final List<Pair<Folder,String>> mFolders = new ArrayList<>();

    /**
     * Starts the activity
     */
    public static Intent getIntent(Context context, Uri foldersUri, Folder inbox, String type) {
        final Intent i = new Intent(context, MailboxSettings.class);
        i.putExtra(EXTRA_FOLDERS_URI, foldersUri);
        i.putExtra(EXTRA_INBOX_ID, inbox.id);
        i.putExtra(EXTRA_TYPE, type);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // This needs to happen before super.onCreate() since that calls onBuildHeaders()
        mInboxId = getIntent().getIntExtra(EXTRA_INBOX_ID, -1);
        mFoldersUri = getIntent().getParcelableExtra(EXTRA_FOLDERS_URI);
        mType = getIntent().getStringExtra(EXTRA_TYPE);

        if (mFoldersUri != null) {
            getLoaderManager().initLoader(FOLDERS_LOADER_ID, null,
                    new MailboxSettingsFolderLoaderCallbacks());
        }

        super.onCreate(savedInstanceState);

        // Always show "app up" as we expect our parent to be an Email activity.
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);
            // Hide the app icon.
            actionBar.setIcon(android.R.color.transparent);
            actionBar.setDisplayUseLogoEnabled(false);
            if (mType != null && mType.equals(PREFERENCE_SYNC_SETTINGS)) {
                actionBar.setTitle(getString(R.string.mailbox_settings_activity_title));
            } else if (mType != null && mType.equals(PREFERENCE_PER_FOLDER_NOTIFICATIONS)) {
                actionBar.setTitle(getString(R.string.mailbox_notify_settings_activity_title));
            }
        }
    }

    @Override
    public void onBuildHeaders(List<Header> target) {
        if (mFolders.isEmpty()) {
            final Header dummy = new Header();
            dummy.titleRes = R.string.mailbox_name_display_inbox;
            dummy.fragment = MailboxSettingsFragment.class.getName();
            dummy.fragmentArguments = MailboxSettingsFragment.getArguments(mInboxId, null);

        } else {
            for (final Pair<Folder, String> f : mFolders) {
                final Header h = new Header();
                h.title = f.first.name;
                setMailboxHeaderIcon(h, f.first);
                h.extras = new Bundle();
                h.extras.putInt(EXTRA_HEADER_FOLDER_INDENT, f.second.split("\\/").length - 1);
                if (mType != null && mType.equals(PREFERENCE_SYNC_SETTINGS)) {
                    h.fragment = MailboxSettingsFragment.class.getName();
                    h.fragmentArguments = MailboxSettingsFragment.getArguments(f.first.id, mType);
                } else if (mType != null && mType.equals(PREFERENCE_PER_FOLDER_NOTIFICATIONS)) {
                    h.fragment = MailboxNotificationsFragment.class.getName();
                    h.fragmentArguments = MailboxNotificationsFragment.getArguments(
                            f.first, mType);
                }
                target.add(h);
            }
        }

        setListAdapter(new MailboxHeadersAdapter(this, target));
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        // Activity is not exported
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setMailboxHeaderIcon(Header header, Folder folder) {
        if (folder.isSent()) {
            header.iconRes = R.drawable.ic_drawer_sent_24dp;
        } else if (folder.isInbox()) {
            header.iconRes = R.drawable.ic_drawer_inbox_24dp;
        } else {
            header.iconRes = folder.hasChildren ? R.drawable.ic_folder_parent_24dp
                : R.drawable.ic_drawer_folder_24dp;
        }
    }

    /**
     * Setup the entries and entry values for the sync lookback preference
     * @param context the caller's context
     * @param pref a ListPreference to be set up
     * @param maxLookback The maximum lookback allowed, or 0 if no max.
     * @param showWithDefault Whether to show the version with default, or without.
     */
    public static void setupLookbackPreferenceOptions(final Context context,
            final ListPreference pref, final int maxLookback, final boolean showWithDefault) {
        final Resources resources = context.getResources();
        // Load the complete list of entries/values
        CharSequence[] entries;
        CharSequence[] values;
        final int offset;
        if (showWithDefault) {
            entries = resources.getTextArray(
                    R.array.account_settings_mail_window_entries_with_default);
            values = resources.getTextArray(
                    R.array.account_settings_mail_window_values_with_default);
            offset = 1;
        } else {
            entries = resources.getTextArray(R.array.account_settings_mail_window_entries);
            values = resources.getTextArray(R.array.account_settings_mail_window_values);
            offset = 0;
        }
        // If we have a maximum lookback policy, enforce it
        if (maxLookback > 0) {
            final int size = maxLookback + offset;
            entries = Arrays.copyOf(entries, size);
            values = Arrays.copyOf(values, size);
        }
        // Set up the preference
        pref.setEntries(entries);
        pref.setEntryValues(values);
        pref.setSummary(pref.getEntry());
    }

    private static class MailboxHeadersAdapter extends ArrayAdapter<Header> {
        private static class HeaderViewHolder {
            View spacer;
            ImageView icon;
            TextView title;
        }

        private LayoutInflater mInflater;
        private int mFolderIndent;

        public MailboxHeadersAdapter(Context context, List<Header> objects) {
            super(context, 0, objects);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mFolderIndent = (int) context.getResources().getDimension(R.dimen.child_folder_indent);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            HeaderViewHolder holder;
            View view;

            if (convertView == null) {
                view = mInflater.inflate(R.layout.preference_mailbox_item, parent, false);
                holder = new HeaderViewHolder();
                holder.spacer = view.findViewById(R.id.spacer);
                holder.icon = (ImageView) view.findViewById(R.id.icon);
                holder.title = (TextView) view.findViewById(android.R.id.title);
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (HeaderViewHolder) view.getTag();
            }

            // All view fields must be updated every time, because the view may be recycled
            Header header = getItem(position);
            int headerIndent = header.extras.getInt(EXTRA_HEADER_FOLDER_INDENT, 0);
            holder.spacer.getLayoutParams().width = mFolderIndent * headerIndent;
            holder.icon.setImageResource(header.iconRes);
            holder.title.setText(header.getTitle(getContext().getResources()));
            return view;
        }
    }

    private class MailboxSettingsFolderLoaderCallbacks
            implements LoaderManager.LoaderCallbacks<Cursor> {

        @Override
        public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
            return new CursorLoader(MailboxSettings.this, mFoldersUri,
                    UIProvider.FOLDERS_PROJECTION, null, null, null);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
            if (cursor == null) {
                return;
            }
            mFolders.clear();

            // Convert the cursor to an temp array and map all the folders
            Map<Uri, Folder> folders = new HashMap<>();
            List<Folder> tmp = new ArrayList<>();
            Folder inbox = null;
            Folder sent = null;
            while(cursor.moveToNext()) {
                final Folder folder = new Folder(cursor);
                if (!folder.supportsCapability(UIProvider.FolderCapabilities.IS_VIRTUAL) &&
                        !folder.isTrash() && !folder.isDraft() && !folder.isOutbox()) {
                    if (folder.id == mInboxId) {
                        inbox = folder;
                    } else if (folder.isSent()) {
                        sent = folder;
                    }
                    tmp.add(folder);
                    folders.put(folder.folderUri.fullUri, folder);
                }
            }

            // Create the hierarchical paths of all the folders
            int count = tmp.size();
            for (int i = 0; i < count; i++) {
                Folder folder = tmp.get(i);
                mFolders.add(new Pair<Folder, String>(folder,
                        getHierarchicalFolder(folder, folders)));
            }

            // Sort folders by hierarchical path
            final String inboxFolderName = inbox.name;
            final String sentFolderName = sent.name;
            Collections.sort(mFolders, new Comparator<Pair<Folder, String>>() {
                private final Collator mCollator = Collator.getInstance();
                @Override
                public int compare(Pair<Folder, String> lhs, Pair<Folder, String> rhs) {
                    boolean lInbox = lhs.second.startsWith(inboxFolderName);
                    boolean rInbox = rhs.second.startsWith(inboxFolderName);
                    boolean lSent = lhs.second.startsWith(sentFolderName);
                    boolean rSent = rhs.second.startsWith(sentFolderName);
                    String lParent = getHierarchicalParentFolder(lhs.second);
                    String rParent = getHierarchicalParentFolder(rhs.second);
                    if (lInbox && !rInbox) {
                        return -1;
                    } else if (!lInbox && rInbox) {
                        return 1;
                    }
                    if (lSent && !rSent) {
                        return -1;
                    } else if (!lSent && rSent) {
                        return 1;
                    }
                    if (lhs.second.startsWith(rhs.second)) {
                        return 1;
                    }
                    if (rhs.second.startsWith(lhs.second)) {
                        return -1;
                    }
                    if (lParent != null && rParent != null && lParent.equals(rParent)) {
                        return mCollator.compare(lhs.first.name, rhs.first.name);
                    }
                    return mCollator.compare(lhs.second, rhs.second);
                }
            });

            invalidateHeaders();
        }

        @Override
        public void onLoaderReset(Loader<Cursor> cursorLoader) {
            mFolders.clear();
        }

        private String getHierarchicalFolder(Folder folder, Map<Uri, Folder> folders) {
            if (!TextUtils.isEmpty(folder.hierarchicalDesc)) {
                return folder.hierarchicalDesc;
            }
            String name = folder.name;
            Folder tmp = folder;
            while (tmp != null && tmp.parent != null && !tmp.parent.toString().isEmpty()) {
                tmp = folders.get(tmp.parent);
                if (tmp != null) {
                    name = tmp.name + "/" + name;
                }
            }
            return name;
        }

        private String getHierarchicalParentFolder(String folder) {
            int pos = folder.lastIndexOf("/");
            if (pos != -1) {
                return folder.substring(0, pos);
            }
            return null;
        }
    }

    public static class MailboxNotificationsFragment extends PreferenceFragment {
        private static final String EXTRA_MAILBOX_ID = "MailboxId";
        private static final String EXTRA_MAILBOX_PERSISTEND_ID = "MailboxPersistentId";
        private static final String EXTRA_MAILBOX_IS_INBOX = "MailboxIsInbox";

        private static final String BUNDLE_ACCOUNT = "MailboxNotifySettings.account";
        private static final String BUNDLE_MAILBOX = "MailboxNotifySettings.mailbox";
        private static final String BUNDLE_NOTIF_ENABLED = "MailboxNotifySettings.enabled";
        private static final String BUNDLE_NOTIF_RINGTONE = "MailboxSettings.ringtone";
        private static final String BUNDLE_NOTIF_VIBRATE = "MailboxSettings.vibrate";

        private static final String PREF_NOTIF_ENABLED_KEY = "notifications-enabled";
        private static final String PREF_NOTIF_RINGTONE_KEY = "notification-ringtone";
        private static final String PREF_NOTIF_VIBRATE_KEY = "notification-vibrate";

        private static final int RINGTONE_REQUEST_CODE =
                MailboxNotificationsFragment.class.hashCode();

        private FolderPreferences mPreferences;
        private Account mAccount;
        private Mailbox mMailbox;

        private CheckBoxPreference mPrefNotifEnabled;
        private Preference mPrefNotifRingtone;
        private CheckBoxPreference mPrefNotifVibrate;

        private boolean mOldMailboxEnabled;
        private String mOldMailboxRingtone;
        private boolean mOldMailboxVibrate;

        private Uri mRingtoneUri;
        private Ringtone mRingtone;

        private static Bundle getArguments(Folder folder, String type) {
            final Bundle b = new Bundle(4);
            b.putLong(EXTRA_MAILBOX_ID, folder.id);
            b.putString(EXTRA_MAILBOX_PERSISTEND_ID, folder.persistentId);
            b.putBoolean(EXTRA_MAILBOX_IS_INBOX, folder.isInbox());
            b.putString(EXTRA_TYPE, type);
            return b;
        }

        public MailboxNotificationsFragment() {}

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            final long mailboxId = getArguments().getLong(EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);
            final String mailboxPersistenId = getArguments().getString(
                    EXTRA_MAILBOX_PERSISTEND_ID, null);
            final boolean mailboxIsInbox = getArguments().getBoolean(EXTRA_MAILBOX_IS_INBOX, false);
            if (mailboxId == Mailbox.NO_MAILBOX || mailboxPersistenId == null) {
                getActivity().finish();
            }

            addPreferencesFromResource(R.xml.mailbox_notifications_preferences);

            mPrefNotifEnabled = (CheckBoxPreference) findPreference(PREF_NOTIF_ENABLED_KEY);
            mPrefNotifRingtone = findPreference(PREF_NOTIF_RINGTONE_KEY);
            mPrefNotifRingtone.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showRingtonePicker();
                    return true;
                }
            });
            mPrefNotifVibrate = (CheckBoxPreference) findPreference(PREF_NOTIF_VIBRATE_KEY);
            Vibrator vibrator = (Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE);
            if (!vibrator.hasVibrator()) {
                mPrefNotifVibrate.setChecked(false);
                getPreferenceScreen().removePreference(mPrefNotifVibrate);
                mPrefNotifVibrate = null;
            }

            if (savedInstanceState != null) {
                mAccount = savedInstanceState.getParcelable(BUNDLE_ACCOUNT);
                mMailbox = savedInstanceState.getParcelable(BUNDLE_MAILBOX);
                mPreferences = new FolderPreferences(getActivity(), mAccount.mEmailAddress,
                        mailboxPersistenId, mailboxIsInbox);

                mPrefNotifEnabled.setChecked(savedInstanceState.getBoolean(BUNDLE_NOTIF_ENABLED));
                setRingtone(savedInstanceState.getString(BUNDLE_NOTIF_RINGTONE));
                if (mPrefNotifVibrate != null) {
                    mPrefNotifVibrate.setChecked(
                            savedInstanceState.getBoolean(BUNDLE_NOTIF_VIBRATE));
                }
                onDataLoaded();
            } else {
                // Make them disabled until we load data
                enablePreferences(false);
                getLoaderManager().initLoader(0, getArguments(), new MailboxLoaderCallbacks());
            }
        }

        private void setRingtone(String ringtone) {
            if (!TextUtils.isEmpty(ringtone)) {
                mRingtoneUri = Uri.parse(ringtone);
                mRingtone = RingtoneManager.getRingtone(getActivity(), mRingtoneUri);
            } else {
                mRingtoneUri = null;
                mRingtone = null;
            }
            setRingtoneSummary();
        }

        private void setRingtoneSummary() {
            final String summary = mRingtone != null ? mRingtone.getTitle(getActivity())
                    : getString(R.string.silent_ringtone);
            mPrefNotifRingtone.setSummary(summary);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == RINGTONE_REQUEST_CODE) {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                    String ringtone = "";
                    if (uri != null) {
                        ringtone = uri.toString();
                    }
                    setRingtone(ringtone);
                }
            }
        }

        private void enablePreferences(boolean enabled) {
            mPrefNotifEnabled.setEnabled(enabled);
            mPrefNotifRingtone.setEnabled(enabled);
            if (mPrefNotifVibrate != null) {
                mPrefNotifVibrate.setEnabled(enabled);
            }
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putParcelable(BUNDLE_ACCOUNT, mAccount);
            outState.putParcelable(BUNDLE_MAILBOX, mMailbox);
            outState.putBoolean(BUNDLE_NOTIF_ENABLED, mPrefNotifEnabled.isChecked());
            String ringtoneUri = "";
            if (mRingtoneUri != null) {
                ringtoneUri = mRingtoneUri.toString();
            }
            outState.putString(BUNDLE_NOTIF_RINGTONE, ringtoneUri);
            outState.putBoolean(PREF_NOTIF_VIBRATE_KEY, mPrefNotifVibrate != null
                    ? mPrefNotifVibrate.isChecked() : false);
        }

        /**
         * We save all the settings in onDestroy, *unless it's for configuration changes*.
         */
        @Override
        public void onDestroy() {
            super.onDestroy();
            if (!getActivity().isChangingConfigurations()) {
                savePreferences();
            }
        }

        private void loadOldPreferencesValues() {
            mOldMailboxEnabled = mPreferences.areNotificationsEnabled();
            mOldMailboxRingtone = mPreferences.getNotificationRingtoneUri();
            mOldMailboxVibrate = mPreferences.isNotificationVibrateEnabled();
        }

        /**
         * Shows the system ringtone picker.
         */
        private void showRingtonePicker() {
            Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            final String ringtoneUri = mPreferences.getNotificationRingtoneUri();
            if (!TextUtils.isEmpty(ringtoneUri)) {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        Uri.parse(ringtoneUri));
            }
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    Settings.System.DEFAULT_NOTIFICATION_URI);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                    RingtoneManager.TYPE_NOTIFICATION);
            startActivityForResult(intent, RINGTONE_REQUEST_CODE);
        }

        /**
         * Called when {@link #mMailbox} is loaded (either by the loader or from the saved state).
         */
        private void onDataLoaded() {
            Preconditions.checkNotNull(mAccount);
            Preconditions.checkNotNull(mMailbox);

            // Update the title with the mailbox name.
            final ActionBar actionBar = getActivity().getActionBar();
            final String mailboxName = mMailbox.mDisplayName;
            if (actionBar != null) {
                actionBar.setTitle(mailboxName);
                actionBar.setSubtitle(getString(R.string.mailbox_notify_settings_activity_title));
            } else {
                getActivity().setTitle(
                        getString(R.string.mailbox_notify_settings_activity_title_with_mailbox,
                                mailboxName));
            }
        }

        /**
         * Save changes to the preferences folder backend.
         *
         * Note it's called from {@link #onDestroy()}
         */
        private void savePreferences() {
            if (mPreferences == null) {
                return;
            }

            boolean mailboxEnabled = mPrefNotifEnabled.isChecked();
            String mailboxRingtone = "";
            if (mRingtoneUri != null) {
                mailboxRingtone = mRingtoneUri.toString();
            }
            boolean mailboxVibrate = mPrefNotifVibrate != null
                    ? mPrefNotifVibrate.isChecked() : false;
            if (mailboxEnabled != mOldMailboxEnabled) {
                mPreferences.setNotificationsEnabled(mailboxEnabled);
                mOldMailboxEnabled = mailboxEnabled;
            }
            if (!mailboxRingtone.equals(mOldMailboxRingtone)) {
                mPreferences.setNotificationRingtoneUri(mailboxRingtone);
                mOldMailboxRingtone = mailboxRingtone;
            }
            if (mailboxVibrate != mOldMailboxVibrate) {
                mPreferences.setNotificationVibrateEnabled(mailboxVibrate);
                mOldMailboxVibrate = mailboxVibrate;
            }
        }

        private static class MailboxLoader extends MailAsyncTaskLoader<Map<String, Object>> {

            public static final String RESULT_KEY_MAILBOX = "mailbox";
            public static final String RESULT_KEY_ACCOUNT = "account";

            private final long mMailboxId;

            private MailboxLoader(Context context, long mailboxId) {
                super(context);
                mMailboxId = mailboxId;
            }

            @Override
            public Map<String, Object> loadInBackground() {
                final Map<String, Object> result = new HashMap<>();

                final Mailbox mailbox = Mailbox.restoreMailboxWithId(getContext(), mMailboxId);
                if (mailbox == null) {
                    return null;
                }
                Account account = Account.restoreAccountWithId(getContext(), mailbox.mAccountKey);
                if (account == null) {
                    return null;
                }
                result.put(RESULT_KEY_MAILBOX, mailbox);
                result.put(RESULT_KEY_ACCOUNT, account);
                return result;
            }

            @Override
            protected void onDiscardResult(Map<String, Object> result) {}
        }

        private class MailboxLoaderCallbacks
                implements LoaderManager.LoaderCallbacks<Map<String, Object>> {

            private long mMailboxId;
            private String mMailboxPersistentId;
            private boolean mMailboxIsInbox;

            @Override
            public Loader<Map<String, Object>> onCreateLoader(int id, Bundle args) {
                mMailboxId = getArguments().getLong(EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);
                mMailboxPersistentId = getArguments().getString(EXTRA_MAILBOX_PERSISTEND_ID, null);
                mMailboxIsInbox = getArguments().getBoolean(EXTRA_MAILBOX_IS_INBOX, false);
                return new MailboxLoader(getActivity(), mMailboxId);
            }

            @Override
            public void onLoadFinished(Loader<Map<String, Object>> loader,
                    Map<String, Object> data) {
                final Mailbox mailbox = (Mailbox) (data == null
                        ? null : data.get(MailboxLoader.RESULT_KEY_MAILBOX));
                final Account account = (Account) (data == null
                        ? null : data.get(MailboxLoader.RESULT_KEY_ACCOUNT));
                if (mailbox == null || account == null) {
                    getActivity().finish();
                    return;
                }

                mAccount = account;
                mMailbox = mailbox;
                mPreferences = new FolderPreferences(getActivity(), mAccount.mEmailAddress,
                        mMailboxPersistentId, mMailboxIsInbox);
                loadOldPreferencesValues();

                mPrefNotifEnabled.setChecked(mPreferences.areNotificationsEnabled());
                setRingtone(mPreferences.getNotificationRingtoneUri());
                if (mPrefNotifVibrate != null) {
                    mPrefNotifVibrate.setChecked(mPreferences.isNotificationVibrateEnabled());
                }
                onDataLoaded();
                if (mMailbox.mType != Mailbox.TYPE_DRAFTS) {
                    enablePreferences(true);
                }
            }

            @Override
            public void onLoaderReset(Loader<Map<String, Object>> loader) {}
        }
    }

    public static class MailboxSettingsFragment extends PreferenceFragment {
        private static final String EXTRA_MAILBOX_ID = "MailboxId";

        private static final String BUNDLE_MAILBOX = "MailboxSettings.mailbox";
        private static final String BUNDLE_MAX_LOOKBACK = "MailboxSettings.maxLookback";
        private static final String BUNDLE_SYNC_ENABLED_VALUE = "MailboxSettings.syncEnabled";
        private static final String BUNDLE_SYNC_WINDOW_VALUE = "MailboxSettings.syncWindow";

        private static final String PREF_SYNC_ENABLED_KEY = "sync_enabled";
        private static final String PREF_SYNC_WINDOW_KEY = "sync_window";

        private Mailbox mMailbox;
        /** The maximum lookback allowed for this mailbox, or 0 if no max. */
        private int mMaxLookback;

        private CheckBoxPreference mSyncEnabledPref;
        private ListPreference mSyncLookbackPref;

        private static Bundle getArguments(long mailboxId, String type) {
            final Bundle b = new Bundle(1);
            b.putLong(EXTRA_MAILBOX_ID, mailboxId);
            b.putString(EXTRA_TYPE, type);
            return b;
        }

        public MailboxSettingsFragment() {}

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            final long mailboxId = getArguments().getLong(EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);
            if (mailboxId == Mailbox.NO_MAILBOX) {
                getActivity().finish();
            }

            addPreferencesFromResource(R.xml.mailbox_preferences);

            mSyncEnabledPref = (CheckBoxPreference) findPreference(PREF_SYNC_ENABLED_KEY);
            mSyncLookbackPref = (ListPreference) findPreference(PREF_SYNC_WINDOW_KEY);

            mSyncLookbackPref.setOnPreferenceChangeListener(mPreferenceChanged);

            if (savedInstanceState != null) {
                mMailbox = savedInstanceState.getParcelable(BUNDLE_MAILBOX);
                mMaxLookback = savedInstanceState.getInt(BUNDLE_MAX_LOOKBACK);
                mSyncEnabledPref
                        .setChecked(savedInstanceState.getBoolean(BUNDLE_SYNC_ENABLED_VALUE));
                mSyncLookbackPref.setValue(savedInstanceState.getString(BUNDLE_SYNC_WINDOW_VALUE));
                onDataLoaded();
            } else {
                // Make them disabled until we load data
                enablePreferences(false);
                getLoaderManager().initLoader(0, getArguments(), new MailboxLoaderCallbacks());
            }
        }

        private void enablePreferences(boolean enabled) {
            mSyncEnabledPref.setEnabled(enabled);
            mSyncLookbackPref.setEnabled(enabled);
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putParcelable(BUNDLE_MAILBOX, mMailbox);
            outState.putInt(BUNDLE_MAX_LOOKBACK, mMaxLookback);
            outState.putBoolean(BUNDLE_SYNC_ENABLED_VALUE, mSyncEnabledPref.isChecked());
            outState.putString(BUNDLE_SYNC_WINDOW_VALUE, mSyncLookbackPref.getValue());
        }

        /**
         * We save all the settings in onDestroy, *unless it's for configuration changes*.
         */
        @Override
        public void onDestroy() {
            super.onDestroy();
            if (!getActivity().isChangingConfigurations()) {
                saveToDatabase();
            }
        }

        private static class MailboxLoader extends MailAsyncTaskLoader<Map<String, Object>> {
            /** Projection for loading an account's policy key. */
            private static final String[] POLICY_KEY_PROJECTION =
                    { AccountColumns.POLICY_KEY };
            private static final int POLICY_KEY_COLUMN = 0;

            /** Projection for loading the max email lookback. */
            private static final String[] MAX_EMAIL_LOOKBACK_PROJECTION =
                    { Policy.MAX_EMAIL_LOOKBACK };
            private static final int MAX_EMAIL_LOOKBACK_COLUMN = 0;

            public static final String RESULT_KEY_MAILBOX = "mailbox";
            public static final String RESULT_KEY_MAX_LOOKBACK = "maxLookback";

            private final long mMailboxId;

            private MailboxLoader(Context context, long mailboxId) {
                super(context);
                mMailboxId = mailboxId;
            }

            @Override
            public Map<String, Object> loadInBackground() {
                final Map<String, Object> result = new HashMap<>();

                final Mailbox mailbox = Mailbox.restoreMailboxWithId(getContext(), mMailboxId);
                result.put(RESULT_KEY_MAILBOX, mailbox);
                result.put(RESULT_KEY_MAX_LOOKBACK, 0);

                if (mailbox == null) {
                    return result;
                }

                // Get the max lookback from our policy, if we have one.
                final Long policyKey = Utility.getFirstRowLong(getContext(),
                        ContentUris.withAppendedId(Account.CONTENT_URI, mailbox.mAccountKey),
                        POLICY_KEY_PROJECTION, null, null, null, POLICY_KEY_COLUMN);
                if (policyKey == null) {
                    // No policy, nothing to look up.
                    return result;
                }

                final int maxLookback = Utility.getFirstRowInt(getContext(),
                        ContentUris.withAppendedId(Policy.CONTENT_URI, policyKey),
                        MAX_EMAIL_LOOKBACK_PROJECTION, null, null, null,
                        MAX_EMAIL_LOOKBACK_COLUMN, 0);
                result.put(RESULT_KEY_MAX_LOOKBACK, maxLookback);

                return result;
            }

            @Override
            protected void onDiscardResult(Map<String, Object> result) {}
        }

        private class MailboxLoaderCallbacks
                implements LoaderManager.LoaderCallbacks<Map<String, Object>> {
            @Override
            public Loader<Map<String, Object>> onCreateLoader(int id, Bundle args) {
                final long mailboxId = args.getLong(EXTRA_MAILBOX_ID);
                return new MailboxLoader(getActivity(), mailboxId);
            }

            @Override
            public void onLoadFinished(Loader<Map<String, Object>> loader,
                    Map<String, Object> data) {
                final Mailbox mailbox = (Mailbox)
                        (data == null ? null : data.get(MailboxLoader.RESULT_KEY_MAILBOX));
                if (mailbox == null) {
                    getActivity().finish();
                    return;
                }

                mMailbox = mailbox;
                mMaxLookback = (Integer) data.get(MailboxLoader.RESULT_KEY_MAX_LOOKBACK);

                mSyncEnabledPref.setChecked(mMailbox.mSyncInterval != 0);
                mSyncLookbackPref.setValue(String.valueOf(mMailbox.mSyncLookback));
                onDataLoaded();
                if (mMailbox.mType != Mailbox.TYPE_DRAFTS) {
                    enablePreferences(true);
                }
            }

            @Override
            public void onLoaderReset(Loader<Map<String, Object>> loader) {}
        }

        /**
         * Called when {@link #mMailbox} is loaded (either by the loader or from the saved state).
         */
        private void onDataLoaded() {
            Preconditions.checkNotNull(mMailbox);

            // Update the title with the mailbox name.
            final ActionBar actionBar = getActivity().getActionBar();
            final String mailboxName = mMailbox.mDisplayName;
            if (actionBar != null) {
                actionBar.setTitle(mailboxName);
                actionBar.setSubtitle(getString(R.string.mailbox_settings_activity_title));
            } else {
                getActivity().setTitle(
                        getString(R.string.mailbox_settings_activity_title_with_mailbox,
                                mailboxName));
            }

            MailboxSettings.setupLookbackPreferenceOptions(getActivity(), mSyncLookbackPref,
                    mMaxLookback, true);
        }


        private final OnPreferenceChangeListener mPreferenceChanged =
                new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                mSyncLookbackPref.setValue((String) newValue);
                mSyncLookbackPref.setSummary(mSyncLookbackPref.getEntry());
                return false;
            }
        };

        /**
         * Save changes to the database.
         *
         * Note it's called from {@link #onDestroy()}, which is called on the UI thread where we're
         * not allowed to touch the database, so it uses {@link EmailAsyncTask} to do the save on a
         * bg thread. This unfortunately means there's a chance that the app gets killed before the
         * save is finished.
         */
        private void saveToDatabase() {
            if (mMailbox == null) {
                // We haven't loaded yet, nothing to save.
                return;
            }
            final int syncInterval = mSyncEnabledPref.isChecked() ? 1 : 0;
            final int syncLookback = Integer.valueOf(mSyncLookbackPref.getValue());

            final boolean syncIntervalChanged = syncInterval != mMailbox.mSyncInterval;
            final boolean syncLookbackChanged = syncLookback != mMailbox.mSyncLookback;

            // Only save if a preference has changed value.
            if (!syncIntervalChanged && !syncLookbackChanged) {
                return;
            }

            LogUtils.i(Logging.LOG_TAG, "Saving mailbox settings...");
            enablePreferences(false);

            final long id = mMailbox.mId;
            final Context context = getActivity().getApplicationContext();

            new EmailAsyncTask<Void, Void, Void> (null /* no cancel */) {
                @Override
                protected Void doInBackground(Void... params) {
                    final ContentValues cv = new ContentValues(2);
                    final Uri uri;
                    if (syncIntervalChanged) {
                        cv.put(MailboxColumns.SYNC_INTERVAL, syncInterval);
                    }
                    if (syncLookbackChanged) {
                        cv.put(MailboxColumns.SYNC_LOOKBACK, syncLookback);
                    }
                    uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, id);
                    context.getContentResolver().update(uri, cv, null, null);

                    LogUtils.i(Logging.LOG_TAG, "Saved: " + uri);
                    return null;
                }
            }.executeSerial((Void [])null);
        }
    }
}
