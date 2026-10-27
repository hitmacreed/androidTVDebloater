package com.hitmacreed.androidtvdebloater;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Adds breathing room above and below the dock row (no tray: the tiles sit straight on the shelf light). */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }
}
