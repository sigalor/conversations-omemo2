package de.monocles.chat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import eu.siacs.conversations.R;

public class GifsAdapter extends BaseAdapter {
    private Context ctx;
    private final String[] filesNames;
    private final String[] filesPaths;

    public GifsAdapter(Context ctx, String[] filesNames, String[] filesPaths) {
        this.ctx = ctx;
        this.filesNames = filesNames;
        this.filesPaths = filesPaths;
    }

    @Override
    public int getCount() {
        return filesNames.length;
    }

    @Override
    public Object getItem(int pos) {
        return pos;
    }

    @Override
    public long getItemId(int pos) {
        return pos;
    }

    @Override
    public View getView(int p, View convertView, ViewGroup parent) {
        View grid;
        LayoutInflater inflater = (LayoutInflater) ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        if (convertView == null) {
            grid = inflater.inflate(R.layout.activity_gridview, null);
            ImageView imageView = (ImageView)grid.findViewById(R.id.grid_item);
            Glide.with(ctx).load(filesPaths[p]).into(imageView);
        } else {
            grid = (View) convertView;
        }
        return grid;
    }
}