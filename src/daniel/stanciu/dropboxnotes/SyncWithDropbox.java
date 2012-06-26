package daniel.stanciu.dropboxnotes;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.DropboxAPI.DropboxInputStream;
import com.dropbox.client2.DropboxAPI.Entry;
import com.dropbox.client2.RESTUtility;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.exception.DropboxIOException;
import com.dropbox.client2.exception.DropboxParseException;
import com.dropbox.client2.exception.DropboxServerException;
import com.dropbox.client2.exception.DropboxUnlinkedException;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

public class SyncWithDropbox extends AsyncTask<Void, Integer, Boolean> {
	private static final String TAG = "SyncWithDropbox";
//    private Context mContext;
    private final ProgressDialog mDialog;
    private DropboxAPI<?> mApi;
    private DropboxNotesActivity mActivity;

    private boolean mCanceled = false;
    private String mErrorMsg;
    private ArrayList<String> localNotes = new ArrayList<String>();

    private static final String[] NOTE_DETAILS_PROJECTION = new String[] {
    	NotePad.Notes._ID,
    	NotePad.Notes.COLUMN_NAME_TITLE,
    	NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
    	NotePad.Notes.COLUMN_NAME_NOTE,
    	NotePad.Notes.COLUMN_NAME_DELETED,
    	NotePad.Notes.COLUMN_NAME_FILE_NAME
    };

    public SyncWithDropbox(DropboxNotesActivity activity, DropboxAPI<?> api) {
//    	mContext = context;
    	mApi = api;
    	mActivity = activity;
    	
    	mDialog = new ProgressDialog(activity);
        mDialog.setMessage("Synchronizing notes");
        mDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                mCanceled = true;
                mErrorMsg = "Canceled";

                // This will cancel the getThumbnail operation by closing
                // its stream
//                if (mFos != null) {
//                    try {
//                        mFos.close();
//                    } catch (IOException e) {
//                    }
//                }
            }
        });

        mDialog.show();

    }
    
	@Override
	protected Boolean doInBackground(Void... params) {
		// update Dropbox based on local notes
		Cursor listCursor = mActivity.getContentResolver().query(
				mActivity.getIntent().getData(), NOTE_DETAILS_PROJECTION, null, null, null);
		
		// get details for all notes on Dropbox
		Entry dir = null;
		HashMap<String, Entry> remoteFiles = new HashMap<String, Entry>();
		
		try {
			dir = mApi.metadata("/", 0, null, true, null);
			for (Entry file : dir.contents) {
				if (file.isDeleted) {
					continue;
				}
				String fileName = file.fileName();
				remoteFiles.put(fileName, file);
			}
		} catch (DropboxUnlinkedException e) {
            Log.e(TAG, mErrorMsg, e);
			mErrorMsg = "Please link with dropbox.";
			return false;
        } catch (DropboxIOException e) {
            Log.e(TAG, mErrorMsg, e);
            // Happens all the time, probably want to retry automatically.
            mErrorMsg = "Network error.  Try again.";
            return false;
        } catch (DropboxParseException e) {
            // Probably due to Dropbox server restarting, should retry
            Log.e(TAG, mErrorMsg, e);
            mErrorMsg = "Dropbox error.  Try again.";
            return false;
        } catch (DropboxException e) {
            // Unknown error
            Log.e(TAG, mErrorMsg, e);
            mErrorMsg = "Unknown error.  Try again.";
            return false;
		}
		
		if (listCursor == null) {
			mErrorMsg = "Could not get cursor";
			return false;
		}
		boolean status = true;
		int count = listCursor.getCount();
		int pos = 0;
		int idIndex = listCursor.getColumnIndex(NotePad.Notes._ID);
		int titleIndex = listCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
		int modDateIndex = listCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE);
		int noteIndex = listCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
		int deletedIndex = listCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_DELETED);
		int fileNameIndex = listCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_FILE_NAME);
		
//		int count = mListAdapter.getCount();
		while (listCursor.moveToNext()) {
			if (mCanceled) {
				status = false;
				break;
			}
			long noteId = listCursor.getLong(idIndex);
			String title = listCursor.getString(titleIndex);
			String fileName = listCursor.getString(fileNameIndex);
			if (fileName != null) {
				if (fileName.trim().isEmpty()) {
					fileName = null;
				} else {
					localNotes.add(fileName);
				}
			}
			//String title = mListAdapter.getItem(i).toString();
			Uri uri = ContentUris.withAppendedId(mActivity.getIntent().getData(), noteId);
			String noteContent = listCursor.getString(noteIndex);
			long noteModTime = listCursor.getLong(modDateIndex);
			int noteDeleted = listCursor.getInt(deletedIndex);
			long dropboxNoteModTime;
			try {
				if (DropboxNotesActivity.IS_DEBUGGING) {
					continue;
				}
				if (noteDeleted == 1) {
					if (fileName != null) {
						deleteDropboxNote(uri, fileName);
					}
					mActivity.getContentResolver().delete(uri, null, null);
					continue;
				}
				if (fileName != null) {
					Entry file = remoteFiles.get(fileName);
					if (file != null) {
						dropboxNoteModTime = getFileModDate(file);
						if (dropboxNoteModTime > noteModTime) {
							updateLocalNote(uri, file, dropboxNoteModTime);
						} else if (dropboxNoteModTime < noteModTime) {
							insertDropboxNote(uri, file.path, title, noteContent);
						} else {
							// note unchanged, process next note
							continue;
						}
					} else {
						// TODO: the remote file was deleted, delete local note
						// for now, only show a Toast for it to make sure there are no bugs
						showToast("Cannot find file " + fileName + ". Please check");
					}
				} else {
					insertDropboxNote(uri, noteId, title, noteContent, remoteFiles);
				}
//				dropboxNoteModTime = getFileModDate(noteId);
//				if (dropboxNoteModTime > noteModTime) {
//					updateLocalNote(uri, noteId, dropboxNoteModTime);
//				} else if (dropboxNoteModTime < noteModTime) {
//					insertDropboxNote(uri, noteId, title, noteContent);
//				} else {
//					// note unchanged, process next note
//					continue;
//				}
	        } catch (DropboxUnlinkedException e) {
	            // The AuthSession wasn't properly authenticated or user unlinked.
	            Log.e(TAG, mErrorMsg, e);
	        	mErrorMsg = "Please link with dropbox.";
	        	status = false;
	        	break;
			} catch (DropboxServerException ex) {
	            Log.e(TAG, mErrorMsg, ex);
				if (ex.error == DropboxServerException._404_NOT_FOUND) {
					try {
						if (noteDeleted == 0) {
							insertDropboxNote(uri, noteId, title, noteContent, remoteFiles);
						} else {
							mActivity.getContentResolver().delete(uri, null, null);
						}
					} catch (DropboxUnlinkedException e) {
			            Log.e(TAG, mErrorMsg, e);
						mErrorMsg = "Please link with dropbox.";
						status = false;
						break;
			        } catch (DropboxIOException e) {
			            Log.e(TAG, mErrorMsg, e);
			            // Happens all the time, probably want to retry automatically.
			            mErrorMsg = "Network error.  Try again.";
			            status = false;
			            break;
			        } catch (DropboxParseException e) {
			            // Probably due to Dropbox server restarting, should retry
			            Log.e(TAG, mErrorMsg, e);
			            mErrorMsg = "Dropbox error.  Try again.";
			            status = false;
			            break;
			        } catch (DropboxException e) {
			            // Unknown error
			            Log.e(TAG, mErrorMsg, e);
			            mErrorMsg = "Unknown error.  Try again.";
			            status = false;
			            break;
					}
				} else {
		            mErrorMsg = ex.body.userError;
		            if (mErrorMsg == null) {
		                mErrorMsg = ex.body.error;
		            }
		            status = false;
		            break;
				}
	        } catch (DropboxIOException e) {
	            // Happens all the time, probably want to retry automatically.
	            Log.e(TAG, mErrorMsg, e);
	            mErrorMsg = "Network error.  Try again.";
	            status = false;
	            break;
	        } catch (DropboxParseException e) {
	            // Probably due to Dropbox server restarting, should retry
	            Log.e(TAG, mErrorMsg, e);
	            mErrorMsg = "Dropbox error.  Try again.";
	            status = false;
	            break;
	        } catch (DropboxException e) {
	            // Unknown error
	            Log.e(TAG, mErrorMsg, e);
	            mErrorMsg = "Unknown error.  Try again.";
	            status = false;
	            break;
	        }
			pos++;
			publishProgress(new Integer((int)(100.0*(double)pos/count + 0.5)));
		}
		listCursor.close();
		
		if (!status) {
			return false;
		}
//		for (int i = 0; i < count; i++) {
//			if (mCanceled) {
//				return false;
//			}
//			long noteId = mListAdapter.getItemId(i);
//			Cursor listCursor = mListAdapter.getCursor();
//			String title = listCursor.getString(listCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE));
//			localNotes.add(getFileNameForNoteId(noteId));
//			//String title = mListAdapter.getItem(i).toString();
//			Uri uri = ContentUris.withAppendedId(mActivity.getIntent().getData(), noteId);
//			Cursor cursor;// = managedQuery(uri, NoteEditor.PROJECTION, null, null, null);
//			cursor = mActivity.getContentResolver().query(uri, NOTE_DETAILS_PROJECTION, null, null, null);
//			if (cursor != null && cursor.moveToFirst()) {
//				String noteContent = cursor.getString(noteIndex);
//				long noteModTime = cursor.getLong(modDateIndex);
//				int noteDeleted = cursor.getInt(deletedIndex);
//				cursor.close();
//				long dropboxNoteModTime;
//				try {
//					if (DropboxNotesActivity.IS_DEBUGGING) {
//						continue;
//					}
//					if (noteDeleted == 1) {
//						deleteDropboxNote(uri, noteId);
//						mActivity.getContentResolver().delete(uri, null, null);
//						continue;
//					}
//					dropboxNoteModTime = getFileModDate(noteId);
//					if (dropboxNoteModTime > noteModTime) {
//						updateLocalNote(uri, noteId, dropboxNoteModTime);
//					} else if (dropboxNoteModTime < noteModTime) {
//						insertDropboxNote(uri, noteId, title, noteContent);
//					} else {
//						// note unchanged, process next note
//						continue;
//					}
//		        } catch (DropboxUnlinkedException e) {
//		            // The AuthSession wasn't properly authenticated or user unlinked.
//		            Log.e(TAG, mErrorMsg, e);
//		        	mErrorMsg = "Please link with dropbox.";
//		        	return false;
//				} catch (DropboxServerException ex) {
//		            Log.e(TAG, mErrorMsg, ex);
//					if (ex.error == DropboxServerException._404_NOT_FOUND) {
//						try {
//							if (noteDeleted == 0) {
//								insertDropboxNote(uri, noteId, title, noteContent);
//							} else {
//								mActivity.getContentResolver().delete(uri, null, null);
//							}
//						} catch (DropboxUnlinkedException e) {
//				            Log.e(TAG, mErrorMsg, e);
//							mErrorMsg = "Please link with dropbox.";
//							return false;
//				        } catch (DropboxIOException e) {
//				            Log.e(TAG, mErrorMsg, e);
//				            // Happens all the time, probably want to retry automatically.
//				            mErrorMsg = "Network error.  Try again.";
//				            return false;
//				        } catch (DropboxParseException e) {
//				            // Probably due to Dropbox server restarting, should retry
//				            Log.e(TAG, mErrorMsg, e);
//				            mErrorMsg = "Dropbox error.  Try again.";
//				            return false;
//				        } catch (DropboxException e) {
//				            // Unknown error
//				            Log.e(TAG, mErrorMsg, e);
//				            mErrorMsg = "Unknown error.  Try again.";
//				            return false;
//						}
//					} else {
//			            mErrorMsg = ex.body.userError;
//			            if (mErrorMsg == null) {
//			                mErrorMsg = ex.body.error;
//			            }
//			            return false;
//					}
//		        } catch (DropboxIOException e) {
//		            // Happens all the time, probably want to retry automatically.
//		            Log.e(TAG, mErrorMsg, e);
//		            mErrorMsg = "Network error.  Try again.";
//		            return false;
//		        } catch (DropboxParseException e) {
//		            // Probably due to Dropbox server restarting, should retry
//		            Log.e(TAG, mErrorMsg, e);
//		            mErrorMsg = "Dropbox error.  Try again.";
//		            return false;
//		        } catch (DropboxException e) {
//		            // Unknown error
//		            Log.e(TAG, mErrorMsg, e);
//		            mErrorMsg = "Unknown error.  Try again.";
//		            return false;
//		        }
//			}
//			publishProgress(new Long(i));
//		}
		
		// download extra notes from Dropbox
		try {
			for (Entry file : dir.contents) {
				if (file.isDeleted) {
					continue;
				}
				String fileName = file.fileName();
				if (localNotes.contains(fileName)) {
					continue;
				}
				createLocalNote(file);
			}
		} catch (DropboxUnlinkedException e) {
            Log.e(TAG, mErrorMsg, e);
			mErrorMsg = "Please link with dropbox.";
			return false;
        } catch (DropboxIOException e) {
            Log.e(TAG, mErrorMsg, e);
            // Happens all the time, probably want to retry automatically.
            mErrorMsg = "Network error.  Try again.";
            return false;
        } catch (DropboxParseException e) {
            // Probably due to Dropbox server restarting, should retry
            Log.e(TAG, mErrorMsg, e);
            mErrorMsg = "Dropbox error.  Try again.";
            return false;
        } catch (DropboxException e) {
            // Unknown error
            Log.e(TAG, mErrorMsg, e);
            mErrorMsg = "Unknown error.  Try again.";
            return false;
		}

		return true;
	}

	private void deleteDropboxNote(Uri uri, String fileName) throws DropboxException {
		//String notePath = getPathForNoteId(noteId);
		mApi.delete("/" + fileName);
	}

	private void createLocalNote(Entry file) throws DropboxException {
		Uri uri = mActivity.getContentResolver().insert(mActivity.getIntent().getData(), null);
//		long noteId = Long.parseLong(uri.getPathSegments().get(NotePad.Notes.NOTE_ID_PATH_POSITION));
		updateLocalNote(uri, file, RESTUtility.parseDate(file.modified).getTime());
		//mApi.move(file.path, getPathForNoteId(noteId));
	}
	
//	private void updateLocalNote(Uri uri, long noteId, long dropboxNoteModTime) throws DropboxException {
//		String notePath = getPathForNoteId(noteId);
//		updateLocalNote(uri, notePath, dropboxNoteModTime);
//	}

	private void updateLocalNote(Uri uri, Entry file, long dropboxNoteModTime) throws DropboxException {
		DropboxInputStream is = mApi.getFileStream(file.path, null);
		BufferedReader br = new BufferedReader(new InputStreamReader(is));
		String title = "";
		StringBuffer content = new StringBuffer();
		char [] buffer = new char[1024];
		int readChars = -1;
		try {
			title = br.readLine();
			while ((readChars = br.read(buffer, 0, 1024)) != -1) {
				content.append(buffer, 0, readChars);
			}
			ContentValues values = new ContentValues();
			values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, dropboxNoteModTime);
			values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
			values.put(NotePad.Notes.COLUMN_NAME_NOTE, content.toString());
			values.put(NotePad.Notes.COLUMN_NAME_FILE_NAME, file.fileName());
			mActivity.getContentResolver().update(uri, values, null, null);
		} catch (IOException e) {
			Log.e(TAG, "Download error", e);
		}
		try {
			is.close();
		} catch (IOException e) {
			Log.e(TAG, "Stream close error", e);
		}
	}

	private String getFileNameForNoteId(long noteId) {
		return "Note" + noteId + ".txt";
	}
	
//	private String getPathForNoteId(long noteId) {
//		return "/" + getFileNameForNoteId(noteId);
//	}
//	
	private void insertDropboxNote(Uri noteUri, long noteId, String title,
			String noteContent, HashMap<String, Entry> remoteFiles) throws DropboxException {
		if (DropboxNotesActivity.IS_DEBUGGING) {
			return;
		}
        String fileName;
        do {
        	fileName = getFileNameForNoteId(noteId);
        	noteId++;
        } while (remoteFiles.containsKey(fileName));
        insertDropboxNote(noteUri, "/" + fileName, title, noteContent);
	}
	
	private void insertDropboxNote(Uri noteUri, String path, String title, String noteContent) throws DropboxException {
		String dropboxContent = title + "\n" + noteContent;
        ByteArrayInputStream bais = new ByteArrayInputStream(dropboxContent.getBytes());
       	Entry entry = mApi.putFileOverwrite(path, bais, dropboxContent.getBytes().length, null);
       	updateNoteModificationTimeAndFileName(noteUri, RESTUtility.parseDate(entry.modified).getTime(), entry.fileName());
	}

	private void updateNoteModificationTimeAndFileName(Uri noteUri, long time, String fileName) {
		ContentValues values = new ContentValues();
		values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, time);
		values.put(NotePad.Notes.COLUMN_NAME_FILE_NAME, fileName);
		mActivity.getContentResolver().update(noteUri, values, null, null);
	}

//	private long getFileModDate(long noteId) throws DropboxException {
//		String path = getPathForNoteId(noteId);
//		DropboxAPI.Entry entry = mApi.metadata(path, 1, null, false, null);
//		return getFileModDate(entry);
//	}
	
	private long getFileModDate(Entry file) {
		Date modDate = RESTUtility.parseDate(file.modified);
		return modDate.getTime();
	}

	@Override
    protected void onProgressUpdate(Integer... progress) {
        int percent = progress[0];
        mDialog.setProgress(percent);
    }

    @Override
    protected void onPostExecute(Boolean result) {
        mDialog.dismiss();
        if (result) {
            // Set the image now that we have it
//            mView.setImageDrawable(mDrawable);
        } else {
            // Couldn't download it, so show an error
            showToast(mErrorMsg);
        }
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mActivity, msg, Toast.LENGTH_LONG);
        error.show();
    }


}
