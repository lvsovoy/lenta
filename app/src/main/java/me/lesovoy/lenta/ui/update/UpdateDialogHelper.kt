package me.lesovoy.lenta.ui.update

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import me.lesovoy.lenta.data.update.AppReleaseInfo
import me.lesovoy.lenta.data.update.AppUpdateManager

/**
 * Displays an alert dialog presenting available update information and download options.
 */
object UpdateDialogHelper {

    fun showUpdateDialog(
        activity: Activity,
        releaseInfo: AppReleaseInfo,
        onDismiss: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val context = activity
        val updateManager = AppUpdateManager(context)

        val onSurfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
        val onSurfaceVariantColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurfaceColor)
        val surfaceContainerColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0)
        )

        // Create container view with scrollable changelog
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val tvIntro = TextView(context).apply {
            text = context.getString(R.string.update_available_message, releaseInfo.tagName)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceVariantColor)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        container.addView(tvIntro)

        if (releaseInfo.apkFileSize > 0L) {
            val tvSize = TextView(context).apply {
                text = "Size: " + ThumbnailManager.formatCacheSize(releaseInfo.apkFileSize)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(onSurfaceVariantColor)
                setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            }
            container.addView(tvSize)
        }

        val changelog = AppUpdateManager.formatChangelog(releaseInfo.releaseNotes, releaseInfo.commitMessages)
        if (changelog.isNotBlank()) {
            val tvHeader = TextView(context).apply {
                text = if (releaseInfo.commitMessages.isNotEmpty()) {
                    context.getString(R.string.update_commits_header)
                } else {
                    context.getString(R.string.update_changelog_header)
                }
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setTextColor(onSurfaceColor)
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
            }
            container.addView(tvHeader)

            val scrollView = NestedScrollView(context).apply {
                val maxHeight = (220 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvChangelog = TextView(context).apply {
                text = changelog
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(onSurfaceVariantColor)
                val padBox = (12 * resources.displayMetrics.density).toInt()
                setPadding(padBox, padBox, padBox, padBox)
                setBackgroundResource(R.drawable.rounded_shape)
                backgroundTintList = ColorStateList.valueOf(surfaceContainerColor)
            }
            scrollView.addView(tvChangelog)
            container.addView(scrollView)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(context.getString(R.string.update_available_title, releaseInfo.tagName))
            .setView(container)
            .setPositiveButton(R.string.update_download_button) { _, _ ->
                updateManager.downloadUpdate(releaseInfo)
            }
            .setNeutralButton(R.string.update_view_release) { _, _ ->
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.releasePageUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(R.string.update_dismiss_button) { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setOnDismissListener {
                onDismiss?.invoke()
            }
            .show()
    }
}
