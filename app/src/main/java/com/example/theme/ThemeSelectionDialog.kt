package com.example.theme

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.media.TvFocusAnimator

class ThemeSelectionDialog(
    private val context: Context,
    private val onThemeChanged: (AppTheme) -> Unit
) {

    companion object {
        fun show(context: Context, onThemeChanged: (AppTheme) -> Unit) {
            ThemeSelectionDialog(context, onThemeChanged).show()
        }
    }

    fun show() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_theme_picker, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val rvThemes = view.findViewById<RecyclerView>(R.id.rv_theme_list)
        val btnClose = view.findViewById<Button>(R.id.btn_close_theme_dialog)

        TvFocusAnimator.attach(btnClose)
        btnClose.setOnClickListener { dialog.dismiss() }

        rvThemes.layoutManager = LinearLayoutManager(context)
        rvThemes.adapter = ThemeAdapter(AppTheme.values().toList(), ThemeManager.getTheme()) { selectedTheme ->
            ThemeManager.setTheme(context, selectedTheme)
            onThemeChanged(selectedTheme)
            dialog.dismiss()
        }

        dialog.show()
    }

    private class ThemeAdapter(
        private val items: List<AppTheme>,
        private var selectedTheme: AppTheme,
        private val onSelect: (AppTheme) -> Unit
    ) : RecyclerView.Adapter<ThemeAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val swatch: View = view.findViewById(R.id.view_theme_swatch)
            val tvName: TextView = view.findViewById(R.id.tv_theme_name)
            val tvSubtitle: TextView = view.findViewById(R.id.tv_theme_subtitle)
            val ivCheck: ImageView = view.findViewById(R.id.iv_theme_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_theme_choice, parent, false)
            TvFocusAnimator.attach(view)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.title
            holder.tvSubtitle.text = item.subtitle

            // Swatch circle
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(item.accentColor)
                setStroke(2, Color.WHITE)
            }
            holder.swatch.background = circle

            val isCurrent = item == selectedTheme
            holder.ivCheck.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.ivCheck.setColorFilter(item.accentColor)

            holder.itemView.setOnClickListener {
                selectedTheme = item
                notifyDataSetChanged()
                onSelect(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
