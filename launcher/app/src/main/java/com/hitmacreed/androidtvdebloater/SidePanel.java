package com.hitmacreed.androidtvdebloater;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Options list that slides in from the right edge, styled like the home screen instead of a stock dialog. */
final class SidePanel {

    private static final int WIDTH_DP = 380;

    private SidePanel() {}

    static Dialog show(Context ctx, String title, List<String> labels, List<Runnable> actions) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LayoutInflater inflater = LayoutInflater.from(ctx);
        Dialog dialog = new Dialog(ctx, R.style.SidePanel);
        View panel = inflater.inflate(R.layout.side_panel, null);
        Fonts.applyWeights(panel);
        ((TextView) panel.findViewById(R.id.panelTitle)).setText(title);

        LinearLayout rows = panel.findViewById(R.id.panelRows);
        for (int i = 0; i < labels.size(); i++) {
            TextView row = (TextView) inflater.inflate(R.layout.item_panel_row, rows, false);
            Fonts.setOutfitWeight(row, "'wght' 500");
            row.setText(labels.get(i));
            Runnable action = actions.get(i);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
            rows.addView(row);
        }

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout((int) (WIDTH_DP * density), ViewGroup.LayoutParams.MATCH_PARENT);
            w.setGravity(Gravity.END);
        }
        dialog.show();

        panel.setTranslationX(WIDTH_DP * density);
        panel.animate().translationX(0f).setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
        return dialog;
    }
}
