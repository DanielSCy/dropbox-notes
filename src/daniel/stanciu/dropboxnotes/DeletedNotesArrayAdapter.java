package daniel.stanciu.dropboxnotes;

import java.util.List;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

public class DeletedNotesArrayAdapter extends ArrayAdapter<ContentValues> {
	
	public static final String ID_KEY = "id";
	public static final String TITLE_KEY = "title";
	public static final String FILE_NAME_KEY = "file_name";
	public static final String FOLDER_KEY = "folder";
	
	private LayoutInflater mInflater;
	private int mResource = android.R.layout.simple_list_item_multiple_choice;

	public DeletedNotesArrayAdapter(Context context, List<ContentValues> objects) {
		super(context, android.R.layout.simple_list_item_multiple_choice, objects);
		mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        TextView text;

        if (convertView == null) {
            view = mInflater.inflate(mResource, parent, false);
        } else {
            view = convertView;
        }

        try {
            text = (TextView) view;
        } catch (ClassCastException e) {
            Log.e("ArrayAdapter", "You must supply a resource ID for a TextView");
            throw new IllegalStateException(
                    "ArrayAdapter requires the resource ID to be a TextView", e);
        }

        ContentValues item = getItem(position);
        String folder = item.getAsString(FOLDER_KEY);
        if (folder == null || folder.isEmpty() || folder.equals("/")) {
        	folder = null;
        }
        text.setText("Note " + item.getAsString(TITLE_KEY) + ", file " + item.getAsString(FILE_NAME_KEY) + (folder == null ? "" : " in " + folder));

        return view;

	}
	
	@Override
	public long getItemId(int position) {
		return getItem(position).getAsLong(ID_KEY);
	}
	
	@Override
	public boolean hasStableIds() {
		return true;
	}
}
