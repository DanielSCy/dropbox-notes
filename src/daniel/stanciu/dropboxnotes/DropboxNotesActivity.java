/*
 * Copyright (C) 2007 The Android Open Source Project
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

package daniel.stanciu.dropboxnotes;

import java.util.ArrayList;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.android.AuthActivity;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;
import com.dropbox.client2.session.TokenPair;

import android.app.Dialog;
import android.app.ListActivity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.Toast;

/**
 * Displays a list of notes. Will display notes from the {@link Uri} provided in
 * the incoming Intent if there is one, otherwise it defaults to displaying the
 * contents of the {@link NotePadProvider}.
 * 
 * NOTE: Notice that the provider operations in this Activity are taking place
 * on the UI thread. This is not a good practice. It is only done here to make
 * the code more readable. A real application should use the
 * {@link android.content.AsyncQueryHandler} or {@link android.os.AsyncTask}
 * object to perform operations asynchronously on a separate thread.
 */
public class DropboxNotesActivity extends ListActivity {

	final static private String ACCOUNT_PREFS_NAME = "dropboxprefs";
	final static private String ACCESS_KEY_NAME = "ACCESS_KEY";
	final static private String ACCESS_SECRET_NAME = "ACCESS_SECRET";
	final static private String CURRENT_FOLDER_NAME = "CURRENT_FOLDER";

	// For logging and debugging
	private static final String TAG = "DropboxNotes";

	/**
	 * The columns needed by the cursor adapter
	 */
	private static final String[] PROJECTION = new String[] {
			NotePad.Notes._ID, // 0
			NotePad.Notes.COLUMN_NAME_TITLE, // 1
	};

	/**
	 * The columns needed by the share note action
	 */
	private static final String[] SHARE_PROJECTION = new String[] {
			NotePad.Notes.COLUMN_NAME_TITLE,
			NotePad.Notes.COLUMN_NAME_NOTE
	};
	
	private static final String[] FOLDERS_PROJECTION = new String[] {
		"1 as _id",
		NotePad.Notes.COLUMN_NAME_FOLDER
	};

	// private static final String[] NOTE_DETAILS_PROJECTION = new String[] {
	// NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
	// NotePad.Notes.COLUMN_NAME_NOTE
	// };

	/** The index of the title column */
	private static final int COLUMN_INDEX_TITLE = 1;
	public static final boolean IS_DEBUGGING = false;
	private static final int DYNAMIC_FOLDERS = 56;
	private static final int MOVE_TO_DIALOG_ID = 1;
	private static final int CONFIRM_CLOUD_DELETE_DIALOG_ID = 2;

	DropboxAPI<AndroidAuthSession> mApi;
	private boolean mLoggedIn;
	MenuItem dropboxAuthItem;
	MenuItem dropboxSyncItem;
	// private boolean syncedOnStart = false;
	private boolean mAlreadyLinked = false;
	private Uri mCurrentUri = null;
	private ArrayList<ContentValues> cloudDeletedNotes = null;

	
	private String currentFolder = "";
	private String savedFolder = "";

	/**
	 * onCreate is called when Android starts this Activity from scratch.
	 */
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
		savedFolder = currentFolder = prefs.getString(CURRENT_FOLDER_NAME, "");
		if (currentFolder.isEmpty()) {
			setTitle(R.string.menu_all_notes);
		} else if (currentFolder.equals("/")) {
			setTitle(R.string.menu_root_folder);
		} else {
			setTitle(currentFolder.substring(1, currentFolder.length() - 1));
		}

		// The user does not need to hold down the key to use menu shortcuts.
		setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

		/*
		 * If no data is given in the Intent that started this Activity, then
		 * this Activity was started when the intent filter matched a MAIN
		 * action. We should use the default provider URI.
		 */
		// Gets the intent that started this Activity.
		Intent intent = getIntent();

		// If there is no data associated with the Intent, sets the data to the
		// default URI, which
		// accesses a list of notes.
		if (intent.getData() == null) {
			intent.setData(NotePad.Notes.CONTENT_URI);
		}

		/*
		 * Sets the callback for context menu activation for the ListView. The
		 * listener is set to be this Activity. The effect is that context menus
		 * are enabled for items in the ListView, and the context menu is
		 * handled by a method in NotesList.
		 */
		getListView().setOnCreateContextMenuListener(this);

		/*
		 * Performs a managed query. The Activity handles closing and requerying
		 * the cursor when needed.
		 * 
		 * Please see the introductory note about performing provider operations
		 * on the UI thread.
		 */
		Cursor cursor = getCurrentCursor();

		/*
		 * The following two arrays create a "map" between columns in the cursor
		 * and view IDs for items in the ListView. Each element in the
		 * dataColumns array represents a column name; each element in the
		 * viewID array represents the ID of a View. The SimpleCursorAdapter
		 * maps them in ascending order to determine where each column value
		 * will appear in the ListView.
		 */

		// The names of the cursor columns to display in the view, initialized
		// to the title column
		String[] dataColumns = { NotePad.Notes.COLUMN_NAME_TITLE };

		// The view IDs that will display the cursor columns, initialized to the
		// TextView in
		// noteslist_item.xml
		int[] viewIDs = { android.R.id.text1 };

		// Creates the backing adapter for the ListView.
		@SuppressWarnings("deprecation")
		SimpleCursorAdapter adapter = new SimpleCursorAdapter(this, // The
																	// Context
																	// for the
																	// ListView
				R.layout.noteslist_item, // Points to the XML for a list item
				cursor, // The cursor to get items from
				dataColumns, viewIDs);

		// Sets the ListView's adapter to be the cursor adapter that was just
		// created.
		setListAdapter(adapter);

		if (!IS_DEBUGGING) {
			// Dropbox related
			AndroidAuthSession session = buildSession();
			mApi = new DropboxAPI<AndroidAuthSession>(session);

			checkAppKeySetup();
		}

	}

	@SuppressWarnings("deprecation")
	private Cursor getCurrentCursor() {
		Cursor cursor = null;
		if (currentFolder.isEmpty()) {
			cursor = managedQuery(getIntent().getData(), // Use the
																// default
					// content URI for
					// the provider.
					PROJECTION, // Return the note ID and title for each note.
					" " + NotePad.Notes.COLUMN_NAME_DELETED + " = 0 ", // Only
																		// notes
					// which are
					// not
					// deleted.
					null, // No where clause, therefore no where column values.
					NotePad.Notes.DEFAULT_SORT_ORDER // Use the default sort
														// order.
			);
		} else {
			Uri uri = NotePad.Notes.FOLDER_NAME_URI_BASE;
			cursor = managedQuery(Uri.withAppendedPath(uri, Uri.encode(currentFolder)),
					PROJECTION,
					" " + NotePad.Notes.COLUMN_NAME_DELETED + " = 0 ",
					null,
					NotePad.Notes.DEFAULT_SORT_ORDER);
		}
		return cursor;
	}
	
	@SuppressWarnings("deprecation")
	private void replaceAdapter(String folderPath) {
		SimpleCursorAdapter adapter = (SimpleCursorAdapter)getListAdapter();
		Cursor oldCursor = adapter.getCursor();
		stopManagingCursor(oldCursor); // because it will be closed when changing cursor
		currentFolder = folderPath;
		adapter.changeCursor(getCurrentCursor());
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		if (IS_DEBUGGING) {
			syncWithDropbox();
			return;
		}
		if (mAlreadyLinked) {
			setLoggedIn(true);
			return;
		}
		AndroidAuthSession session = mApi.getSession();

		// The next part must be inserted in the onResume() method of the
		// activity from which session.startAuthentication() was called, so
		// that Dropbox authentication completes properly.
		if (session.authenticationSuccessful()) {
			try {
				// Mandatory call to complete the auth
				session.finishAuthentication();

				// Store it locally in our app for later use
				TokenPair tokens = session.getAccessTokenPair();
				storeKeys(tokens.key, tokens.secret);
				setLoggedIn(true);
				// if(!syncedOnStart ) {
				// syncedOnStart = true;
				syncWithDropbox();
				// }
			} catch (IllegalStateException e) {
				showToast("Couldn't authenticate with Dropbox:"
						+ e.getLocalizedMessage());
				Log.i(TAG, "Error authenticating", e);
			}
		} else if (session.isLinked()) {
			setLoggedIn(true);
			// if(!syncedOnStart ) {
			// syncedOnStart = true;
			// syncWithDropbox();
			// }
		}
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		if (!savedFolder.equals(currentFolder)) {
			SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
			Editor editor = prefs.edit();
			editor.putString(CURRENT_FOLDER_NAME, currentFolder);
			editor.commit();
		}
	}

	private void syncWithDropbox() {
		SyncWithDropbox syncTask = new SyncWithDropbox(this, mApi);
		syncTask.execute();
	}

	private void logOut() {
		// Remove credentials from the session
		mApi.getSession().unlink();

		// Clear our stored keys
		clearKeys();
		// Change UI state to display logged out version
		setLoggedIn(false);
	}

	private void setLoggedIn(boolean loggedIn) {
		mLoggedIn = loggedIn;
		if (dropboxAuthItem != null) {
			if (loggedIn) {
				dropboxAuthItem.setTitle(R.string.menu_signout);
			} else {
				dropboxAuthItem.setTitle(R.string.menu_signin);
			}
		}
		if (dropboxSyncItem != null) {
			if (loggedIn) {
				dropboxSyncItem.setVisible(true);
			} else {
				dropboxSyncItem.setVisible(false);
			}
		}
	}

	private void checkAppKeySetup() {
		// Check to make sure that we have a valid app key
		if (DropboxAppDetails.APP_KEY.startsWith("CHANGE")
				|| DropboxAppDetails.APP_SECRET.startsWith("CHANGE")) {
			showToast("You must apply for an app key and secret from developers.dropbox.com, and add them to the DBRoulette ap before trying it.");
			finish();
			return;
		}

		// Check if the app has set up its manifest properly.
		Intent testIntent = new Intent(Intent.ACTION_VIEW);
		String scheme = "db-" + DropboxAppDetails.APP_KEY;
		String uri = scheme + "://" + AuthActivity.AUTH_VERSION + "/test";
		testIntent.setData(Uri.parse(uri));
		PackageManager pm = getPackageManager();
		if (0 == pm.queryIntentActivities(testIntent, 0).size()) {
			showToast("URL scheme in your app's "
					+ "manifest is not set up correctly. You should have a "
					+ "com.dropbox.client2.android.AuthActivity with the "
					+ "scheme: " + scheme);
			finish();
		}
	}

	private void showToast(String msg) {
		Toast error = Toast.makeText(this, msg, Toast.LENGTH_LONG);
		error.show();
	}

	private String[] getKeys() {
		SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
		String key = prefs.getString(ACCESS_KEY_NAME, null);
		String secret = prefs.getString(ACCESS_SECRET_NAME, null);
		if (key != null && secret != null) {
			String[] ret = new String[2];
			ret[0] = key;
			ret[1] = secret;
			return ret;
		} else {
			return null;
		}
	}

	private void storeKeys(String key, String secret) {
		// Save the access key for later
		SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
		Editor edit = prefs.edit();
		edit.putString(ACCESS_KEY_NAME, key);
		edit.putString(ACCESS_SECRET_NAME, secret);
		edit.commit();
	}

	private void clearKeys() {
		SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
		Editor edit = prefs.edit();
		edit.clear();
		edit.commit();
	}

	private AndroidAuthSession buildSession() {
		AppKeyPair appKeyPair = new AppKeyPair(DropboxAppDetails.APP_KEY,
				DropboxAppDetails.APP_SECRET);
		AndroidAuthSession session;

		String[] stored = getKeys();
		if (stored != null) {
			AccessTokenPair accessToken = new AccessTokenPair(stored[0],
					stored[1]);
			session = new AndroidAuthSession(appKeyPair,
					DropboxAppDetails.ACCESS_TYPE, accessToken);
			mAlreadyLinked = true;
		} else {
			session = new AndroidAuthSession(appKeyPair,
					DropboxAppDetails.ACCESS_TYPE);
			mAlreadyLinked = false;
		}

		return session;
	}

	/**
	 * Called when the user clicks the device's Menu button the first time for
	 * this Activity. Android passes in a Menu object that is populated with
	 * items.
	 * 
	 * Sets up a menu that provides the Insert option plus a list of alternative
	 * actions for this Activity. Other applications that want to handle notes
	 * can "register" themselves in Android by providing an intent filter that
	 * includes the category ALTERNATIVE and the mimeTYpe
	 * NotePad.Notes.CONTENT_TYPE. If they do this, the code in
	 * onCreateOptionsMenu() will add the Activity that contains the intent
	 * filter to its list of options. In effect, the menu will offer the user
	 * other applications that can handle notes.
	 * 
	 * @param menu
	 *            A Menu object, to which menu items should be added.
	 * @return True, always. The menu should be displayed.
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate menu from XML resource
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.list_options_menu, menu);

		dropboxAuthItem = menu.findItem(R.id.menu_dropbox_auth);
		dropboxSyncItem = menu.findItem(R.id.menu_sync);

		// Generate any additional actions that can be performed on the
		// overall list. In a normal install, there are no additional
		// actions found here, but this allows other applications to extend
		// our menu with their own actions.
		Intent intent = new Intent(null, getIntent().getData());
		intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
		menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
				new ComponentName(this, DropboxNotesActivity.class), null,
				intent, 0, null);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);

		if (mLoggedIn) {
			dropboxAuthItem.setTitle(R.string.menu_signout);
			dropboxSyncItem.setVisible(true);
		} else {
			dropboxAuthItem.setTitle(R.string.menu_signin);
			dropboxSyncItem.setVisible(false);
		}
		
		MenuItem folders = menu.findItem(R.id.menu_folders);
		Menu foldersMenu = folders.getSubMenu();
		populateFolders(foldersMenu);

		// The paste menu item is enabled if there is data on the clipboard.
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

		MenuItem mPasteItem = menu.findItem(R.id.menu_paste);

		// If the clipboard contains an item, enables the Paste option on the
		// menu.
		if (clipboard.hasPrimaryClip()) {
			mPasteItem.setEnabled(true);
		} else {
			// If the clipboard is empty, disables the menu's Paste option.
			mPasteItem.setEnabled(false);
		}

		// Gets the number of notes currently being displayed.
		final boolean haveItems = getListAdapter().getCount() > 0;

		// If there are any notes in the list (which implies that one of
		// them is selected), then we need to generate the actions that
		// can be performed on the current selection. This will be a combination
		// of our own specific actions along with any extensions that can be
		// found.
		if (haveItems) {

			// This is the selected item.
			Uri uri = ContentUris.withAppendedId(getIntent().getData(),
					getSelectedItemId());

			// Creates an array of Intents with one element. This will be used
			// to send an Intent
			// based on the selected menu item.
			Intent[] specifics = new Intent[1];

			// Sets the Intent in the array to be an EDIT action on the URI of
			// the selected note.
			specifics[0] = new Intent(Intent.ACTION_EDIT, uri);

			// Creates an array of menu items with one element. This will
			// contain the EDIT option.
			MenuItem[] items = new MenuItem[1];

			// Creates an Intent with no specific action, using the URI of the
			// selected note.
			Intent intent = new Intent(null, uri);

			/*
			 * Adds the category ALTERNATIVE to the Intent, with the note ID URI
			 * as its data. This prepares the Intent as a place to group
			 * alternative options in the menu.
			 */
			intent.addCategory(Intent.CATEGORY_ALTERNATIVE);

			/*
			 * Add alternatives to the menu
			 */
			menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, // Add the Intents
																// as options in
																// the
																// alternatives
																// group.
					Menu.NONE, // A unique item ID is not required.
					Menu.NONE, // The alternatives don't need to be in order.
					null, // The caller's name is not excluded from the group.
					specifics, // These specific options must appear first.
					intent, // These Intent objects map to the options in
							// specifics.
					Menu.NONE, // No flags are required.
					items // The menu items generated from the specifics-to-
							// Intents mapping
			);
			// If the Edit menu item exists, adds shortcuts for it.
			if (items[0] != null) {

				// Sets the Edit menu item shortcut to numeric "1", letter "e"
				items[0].setShortcut('1', 'e');
			}
		} else {
			// If the list is empty, removes any existing alternative actions
			// from the menu
			menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
		}

		// Displays the menu
		return true;
	}

	private void populateFolders(Menu foldersMenu) {
		Cursor cursor = getContentResolver().query(NotePad.Notes.FOLDERS_URI,
				FOLDERS_PROJECTION, null, null, NotePad.Notes.COLUMN_NAME_FOLDER + " ASC");
		
		if (cursor == null) {
			showToast("Cannot get list of folders");
		}
		
		int folderIndex = cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_FOLDER);
		
		foldersMenu.removeGroup(DYNAMIC_FOLDERS);
		
		while (cursor.moveToNext()) {
			String folder = cursor.getString(folderIndex);
			if (folder.equals("/")) {
				continue;
			}
			if (folder.startsWith("/")) {
				folder = folder.substring(1);
			}
			if (folder.endsWith("/")) {
				folder = folder.substring(0, folder.length() - 1);
			}
			//MenuItem mi = foldersMenu.add(folder);
			foldersMenu.add(DYNAMIC_FOLDERS, Menu.NONE, Menu.NONE, folder);
		}
		
		cursor.close();
	}

	/**
	 * This method is called when the user selects an option from the menu, but
	 * no item in the list is selected. If the option was INSERT, then a new
	 * Intent is sent out with action ACTION_INSERT. The data from the incoming
	 * Intent is put into the new Intent. In effect, this triggers the
	 * NoteEditor activity in the NotePad application.
	 * 
	 * If the item was not INSERT, then most likely it was an alternative option
	 * from another application. The parent method is called to process the
	 * item.
	 * 
	 * @param item
	 *            The menu item that was selected by the user
	 * @return True, if the INSERT menu item was selected; otherwise, the result
	 *         of calling the parent method.
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_add:
			/*
			 * Launches a new Activity using an Intent. The intent filter for
			 * the Activity has to have action ACTION_INSERT. No category is
			 * set, so DEFAULT is assumed. In effect, this starts the NoteEditor
			 * Activity in NotePad.
			 */
			Intent intent = new Intent(Intent.ACTION_INSERT, getIntent().getData());
			String targetFolder;
			if (currentFolder.equals("/") || currentFolder.isEmpty()) {
				targetFolder = "/";
			} else {
				targetFolder = currentFolder;
			}
			intent.putExtra(NotePad.Notes.COLUMN_NAME_FOLDER, targetFolder);
			startActivity(intent);
			return true;
		case R.id.menu_paste:
			/*
			 * Launches a new Activity using an Intent. The intent filter for
			 * the Activity has to have action ACTION_PASTE. No category is set,
			 * so DEFAULT is assumed. In effect, this starts the NoteEditor
			 * Activity in NotePad.
			 */
			startActivity(new Intent(Intent.ACTION_PASTE, getIntent().getData()));
			return true;
		case R.id.menu_dropbox_auth:
			if (mLoggedIn) {
				logOut();
			} else {
				// Start the remote authentication
				mApi.getSession().startAuthentication(this);
			}
			return true;
		case R.id.menu_sync:
			if (mLoggedIn) {
				syncWithDropbox();
			}
			return true;
		case R.id.menu_all_notes:
			replaceAdapter("");
			setTitle(R.string.menu_all_notes);
			return true;
		case R.id.menu_root_folder:
			replaceAdapter("/");
			setTitle(R.string.menu_root_folder);
			return true;
		default:
			replaceAdapter("/" + (String) item.getTitle() + "/");
			setTitle(item.getTitle());
			return true;
			//return super.onOptionsItemSelected(item);
		}
	}

	/**
	 * This method is called when the user context-clicks a note in the list.
	 * NotesList registers itself as the handler for context menus in its
	 * ListView (this is done in onCreate()).
	 * 
	 * The only available options are COPY and DELETE.
	 * 
	 * Context-click is equivalent to long-press.
	 * 
	 * @param menu
	 *            A ContexMenu object to which items should be added.
	 * @param view
	 *            The View for which the context menu is being constructed.
	 * @param menuInfo
	 *            Data associated with view.
	 * @throws ClassCastException
	 */
	@Override
	public void onCreateContextMenu(ContextMenu menu, View view,
			ContextMenuInfo menuInfo) {

		// The data from the menu item.
		AdapterView.AdapterContextMenuInfo info;

		// Tries to get the position of the item in the ListView that was
		// long-pressed.
		try {
			// Casts the incoming data object into the type for AdapterView
			// objects.
			info = (AdapterView.AdapterContextMenuInfo) menuInfo;
		} catch (ClassCastException e) {
			// If the menu object can't be cast, logs an error.
			Log.e(TAG, "bad menuInfo", e);
			return;
		}

		/*
		 * Gets the data associated with the item at the selected position.
		 * getItem() returns whatever the backing adapter of the ListView has
		 * associated with the item. In NotesList, the adapter associated all of
		 * the data for a note with its list item. As a result, getItem()
		 * returns that data as a Cursor.
		 */
		Cursor cursor = (Cursor) getListAdapter().getItem(info.position);

		// If the cursor is empty, then for some reason the adapter can't get
		// the data from the
		// provider, so returns null to the caller.
		if (cursor == null) {
			// For some reason the requested item isn't available, do nothing
			return;
		}

		// Inflate menu from XML resource
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.list_context_menu, menu);

		// Sets the menu header to be the title of the selected note.
		menu.setHeaderTitle(cursor.getString(COLUMN_INDEX_TITLE));

		// Append to the
		// menu items for any other activities that can do stuff with it
		// as well. This does a query on the system for any activities that
		// implement the ALTERNATIVE_ACTION for our data, adding a menu item
		// for each one that is found.
		Intent intent = new Intent(null, Uri.withAppendedPath(getIntent()
				.getData(), Integer.toString((int) info.id)));
		intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
		menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
				new ComponentName(this, DropboxNotesActivity.class), null,
				intent, 0, null);
	}

	/**
	 * This method is called when the user selects an item from the context menu
	 * (see onCreateContextMenu()). The only menu items that are actually
	 * handled are DELETE and COPY. Anything else is an alternative option, for
	 * which default handling should be done.
	 * 
	 * @param item
	 *            The selected menu item
	 * @return True if the menu item was DELETE, and no default processing is
	 *         need, otherwise false, which triggers the default handling of the
	 *         item.
	 * @throws ClassCastException
	 */
	@Override
	public boolean onContextItemSelected(MenuItem item) {
		// The data from the menu item.
		AdapterView.AdapterContextMenuInfo info;

		/*
		 * Gets the extra info from the menu item. When an note in the Notes
		 * list is long-pressed, a context menu appears. The menu items for the
		 * menu automatically get the data associated with the note that was
		 * long-pressed. The data comes from the provider that backs the list.
		 * 
		 * The note's data is passed to the context menu creation routine in a
		 * ContextMenuInfo object.
		 * 
		 * When one of the context menu items is clicked, the same data is
		 * passed, along with the note ID, to onContextItemSelected() via the
		 * item parameter.
		 */
		try {
			// Casts the data object in the item into the type for AdapterView
			// objects.
			info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
		} catch (ClassCastException e) {

			// If the object can't be cast, logs an error
			Log.e(TAG, "bad menuInfo", e);

			// Triggers default processing of the menu item.
			return false;
		}
		// Appends the selected note's ID to the URI sent with the incoming Intent.
		Uri noteUri = ContentUris.withAppendedId(getIntent().getData(), info.id);

		/*
		 * Gets the menu item's ID and compares it to known actions.
		 */
		switch (item.getItemId()) {
		case R.id.context_open:
			// Launch activity to view/edit the currently selected item
			startActivity(new Intent(Intent.ACTION_EDIT, noteUri));
			return true;

		case R.id.context_copy:
			// Gets a handle to the clipboard service.
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

			// Copies the notes URI to the clipboard. In effect, this copies the
			// note itself
			clipboard.setPrimaryClip(ClipData.newUri( // new clipboard item
														// holding a URI
					getContentResolver(), // resolver to retrieve URI info
					"Note", // label for the clip
					noteUri) // the URI
					);

			// Returns to the caller and skips further processing.
			return true;

		case R.id.context_delete:

			// Deletes the note from the provider by passing in a URI in note ID format.
			// Please see the introductory note about performing provider
			// operations on the
			// UI thread.
			ContentValues values = new ContentValues();
			values.put(NotePad.Notes.COLUMN_NAME_DELETED, 1);
			getContentResolver().update(noteUri, values, null, null);

			// Returns to the caller and skips further processing.
			return true;
		case R.id.context_generate_qr:
			generateQRCode(noteUri);
			return true;
		case R.id.context_move_to:
			moveToOtherFolder(noteUri);
		default:
			return super.onContextItemSelected(item);
		}
	}

	@SuppressWarnings("deprecation")
	private void moveToOtherFolder(Uri noteUri) {
		mCurrentUri = noteUri;
		showDialog(MOVE_TO_DIALOG_ID);
	}

	

	@SuppressWarnings("deprecation")
	@Override
	protected void onPrepareDialog(int id, Dialog dialog) {
		switch (id) {
		case MOVE_TO_DIALOG_ID:
			ListView foldersList = (ListView)dialog.findViewById(R.id.existingFoldersList);
			Cursor cursor = getContentResolver().query(NotePad.Notes.FOLDERS_URI,
					FOLDERS_PROJECTION, null, null, NotePad.Notes.COLUMN_NAME_FOLDER + " ASC");
			
			if (cursor == null) {
				showToast("Cannot get list of folders");
			}
			
			String[] dataColumns = { NotePad.Notes.COLUMN_NAME_FOLDER };

			// The view IDs that will display the cursor columns, initialized to the
			// TextView in
			// noteslist_item.xml
			int[] viewIDs = { android.R.id.text1 };

			// Creates the backing adapter for the ListView.
			SimpleCursorAdapter adapter = new SimpleCursorAdapter(this, // The
																		// Context
																		// for the
																		// ListView
					R.layout.noteslist_item, // Points to the XML for a list item
					cursor, // The cursor to get items from
					dataColumns, viewIDs);

			foldersList.setAdapter(adapter);
			foldersList.setOnItemClickListener(new AdapterView.OnItemClickListener () {
				@Override
				public void onItemClick(AdapterView<?> list, View view, int position, long id) {
					Cursor cursor = (Cursor)list.getItemAtPosition(position);
					int colIndex = cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_FOLDER);
					String selectedFolder = cursor.getString(colIndex);
					EditText et = (EditText)((View)list.getParent()).findViewById(R.id.new_folder_text);
					et.setText(selectedFolder);
				}
			});
			break;
		case CONFIRM_CLOUD_DELETE_DIALOG_ID:
			if (cloudDeletedNotes != null && cloudDeletedNotes.size() > 0) {
				ListView notesListView = (ListView)dialog.findViewById(R.id.deletedNotesList);
				DeletedNotesArrayAdapter delNotesAdapter = new DeletedNotesArrayAdapter(this, cloudDeletedNotes);
				notesListView.setAdapter(delNotesAdapter);
			}
			break;
		default:
			super.onPrepareDialog(id, dialog);
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case MOVE_TO_DIALOG_ID:
		{
			Dialog dialog = new Dialog(this);
			dialog.setContentView(R.layout.choose_folder_dialog);
			dialog.setTitle(R.string.move_to_title);
			Button okButton = (Button)dialog.findViewById(R.id.okButton);
			Button cancelButton = (Button)dialog.findViewById(R.id.cancelButton);
			
			cancelButton.setTag(dialog);
			okButton.setTag(dialog);
			
			cancelButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					Dialog dialog = (Dialog)v.getTag();
					ListView foldersList = (ListView)dialog.findViewById(R.id.existingFoldersList);
					((SimpleCursorAdapter)foldersList.getAdapter()).getCursor().close();
					dialog.dismiss();
				}
			});
			
			okButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					Dialog dialog = (Dialog)v.getTag();
					EditText newFolder = (EditText)dialog.findViewById(R.id.new_folder_text);
					ListView foldersList = (ListView)dialog.findViewById(R.id.existingFoldersList);
					SimpleCursorAdapter adapter = (SimpleCursorAdapter)foldersList.getAdapter();
					String targetFolder = null;
					if (newFolder.getText().length() > 0) {
						targetFolder = newFolder.getText().toString();
						if (!targetFolder.startsWith("/")) {
							targetFolder = "/" + targetFolder;
						}
						if (!targetFolder.endsWith("/")) {
							targetFolder += "/";
						}
					}
					adapter.getCursor().close();
					if (targetFolder != null) {
						moveToFolder(targetFolder);
					}
					dialog.dismiss();
				}

			});
			
			return dialog;
		}
		case CONFIRM_CLOUD_DELETE_DIALOG_ID:
		{
			Dialog dialog = new Dialog(this);
			dialog.setContentView(R.layout.confirm_cloud_delete_dialog);
			dialog.setTitle(R.string.confirm_cloud_delete_title);
			Button okButton = (Button)dialog.findViewById(R.id.okButton);
			Button cancelButton = (Button)dialog.findViewById(R.id.cancelButton);
			
			cancelButton.setTag(dialog);
			okButton.setTag(dialog);
			
			cancelButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					Dialog dialog = (Dialog)v.getTag();
					cloudDeletedNotes = null;
					dialog.dismiss();
				}
			});
			
			okButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					boolean requery = false;
					Dialog dialog = (Dialog)v.getTag();
					ListView deletedNotesList = (ListView)dialog.findViewById(R.id.deletedNotesList);
					DeletedNotesArrayAdapter adapter = (DeletedNotesArrayAdapter)deletedNotesList.getAdapter();
					SparseBooleanArray checkedPositions = deletedNotesList.getCheckedItemPositions();
					for (int i = 0; i < adapter.getCount(); ++i) {
						Uri uri = ContentUris.withAppendedId(DropboxNotesActivity.this.getIntent().getData(), adapter.getItemId(i));
						if (checkedPositions.get(i)) {
							DropboxNotesActivity.this.getContentResolver().delete(uri, null, null);
							requery = true;
						} else {
							// delete the file name from DB in order to send it to cloud as a new note on next sync operation
							ContentValues updateValues = new ContentValues();
							updateValues.putNull(NotePad.Notes.COLUMN_NAME_FILE_NAME);
							DropboxNotesActivity.this.getContentResolver().update(uri, updateValues, null, null);
						}
					}
					cloudDeletedNotes = null;
					if (requery) {
						((SimpleCursorAdapter)DropboxNotesActivity.this.getListAdapter()).notifyDataSetChanged();
					}
					dialog.dismiss();
				}

			});
			
			return dialog;
		}
		default:
			return super.onCreateDialog(id);
		}
	}

	private void moveToFolder(String targetFolder) {
		// get old note details
		Cursor cursor = getContentResolver().query(mCurrentUri, SHARE_PROJECTION, null, null, null);
		if (cursor == null) {
			showToast("Cannot get cursor");
			mCurrentUri = null;
			return;
		}
		int titleIndex = cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
		int noteIndex = cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
		if (cursor.moveToFirst()) {
			String title = cursor.getString(titleIndex);
			String note = cursor.getString(noteIndex);
			cursor.close();
			// create new note based on the old one
			ContentValues values = new ContentValues();
			values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
			values.put(NotePad.Notes.COLUMN_NAME_NOTE, note);
			values.put(NotePad.Notes.COLUMN_NAME_FOLDER, targetFolder);
			Uri newNoteUri = getContentResolver().insert(NotePad.Notes.CONTENT_URI, values);
			if (newNoteUri == null) {
				showToast("Cannot create new note");
			} else {
				// mark old note as deleted
				values = new ContentValues();
				values.put(NotePad.Notes.COLUMN_NAME_DELETED, 1);
				getContentResolver().update(mCurrentUri, values, null, null);
			}
		}
		mCurrentUri = null;
	}
	
	private void generateQRCode(Uri noteUri) {
		// of the selected note
//		Intent shareIntent = new Intent();
//		shareIntent.setAction(Intent.ACTION_SEND);
//		shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(mFileName)));
//		shareIntent.setType("application/octet-stream");
//		startActivity(Intent.createChooser(shareIntent, getResources().getText(R.string.send_to)));
		Cursor cursor = getContentResolver().query(noteUri, SHARE_PROJECTION, null, null, null);
		if (cursor.moveToFirst()) {
			String noteText = cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE));
			Intent shareIntent = new Intent("com.google.zxing.client.android.ENCODE");
			shareIntent.putExtra("ENCODE_TYPE", "TEXT_TYPE");
			shareIntent.putExtra("ENCODE_DATA", noteText);
			startActivity(Intent.createChooser(shareIntent, getResources().getText(R.string.choose_qr_app)));
		}
	}

	/**
	 * This method is called when the user clicks a note in the displayed list.
	 * 
	 * This method handles incoming actions of either PICK (get data from the
	 * provider) or GET_CONTENT (get or create data). If the incoming action is
	 * EDIT, this method sends a new Intent to start NoteEditor.
	 * 
	 * @param l
	 *            The ListView that contains the clicked item
	 * @param v
	 *            The View of the individual item
	 * @param position
	 *            The position of v in the displayed list
	 * @param id
	 *            The row ID of the clicked item
	 */
	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {

		// Constructs a new URI from the incoming URI and the row ID
		Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);

		// Gets the action from the incoming Intent
		String action = getIntent().getAction();

		// Handles requests for note data
		if (Intent.ACTION_PICK.equals(action)
				|| Intent.ACTION_GET_CONTENT.equals(action)) {

			// Sets the result to return to the component that called this
			// Activity. The
			// result contains the new URI
			setResult(RESULT_OK, new Intent().setData(uri));
		} else {

			// Sends out an Intent to start an Activity that can handle
			// ACTION_EDIT. The
			// Intent's data is the note ID URI. The effect is to call NoteEdit.
			startActivity(new Intent(Intent.ACTION_EDIT, uri));
		}
	}

	@SuppressWarnings("deprecation")
	public void showCloudDeletedConfirmation(ArrayList<ContentValues> deletedInCloud) {
		if (deletedInCloud != null && deletedInCloud.size() > 0) {
			cloudDeletedNotes = deletedInCloud;
			showDialog(CONFIRM_CLOUD_DELETE_DIALOG_ID);
		}
	}
}
