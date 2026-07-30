package com.andrerinas.headunitrevived.main

import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.QrCodeGenerator
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dedicated support screen: a clickable link to the project's GitHub issues, a QR code of that
 * same URL (so a head unit without internet can be scanned from a phone), a short how-to, and a
 * note that opening an issue needs a GitHub account.
 */
class SupportFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_support, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val url = getString(R.string.github_issues_url)

        // Clickable link that opens the system browser if the head unit has one.
        val link = view.findViewById<TextView>(R.id.support_link)
        link.text = fromHtml("<a href=\"$url\">${getString(R.string.support_open_issues)}</a>")
        link.movementMethod = LinkMovementMethod.getInstance()

        view.findViewById<MaterialButton>(R.id.support_rules_button).setOnClickListener {
            showRulesDialog()
        }

        // QR of the same URL. Generated offline, so it works without internet on the head unit.
        val qr = view.findViewById<ImageView>(R.id.support_qr)
        val bitmap = QrCodeGenerator.generateQrCode(url, 500)
        if (bitmap != null) {
            qr.setImageBitmap(bitmap)
        } else {
            // Generation failed: hide the QR block so we do not show an empty white box.
            view.findViewById<View>(R.id.support_qr_container).visibility = View.GONE
            view.findViewById<View>(R.id.support_qr_caption).visibility = View.GONE
        }
    }

    /** Modal with the basic rules for opening a good, actionable issue. */
    private fun showRulesDialog() {
        val rules = listOf(
            R.string.support_rules_english_title to R.string.support_rules_english_desc,
            R.string.support_rules_device_title to R.string.support_rules_device_desc,
            R.string.support_rules_connection_title to R.string.support_rules_connection_desc,
            R.string.support_rules_appversion_title to R.string.support_rules_appversion_desc,
            R.string.support_rules_logs_title to R.string.support_rules_logs_desc,
            R.string.support_rules_search_title to R.string.support_rules_search_desc,
            R.string.support_rules_phone_title to R.string.support_rules_phone_desc,
            R.string.support_rules_describe_title to R.string.support_rules_describe_desc,
            R.string.support_rules_oneissue_title to R.string.support_rules_oneissue_desc
        )
        // The first rule (English-only) is highlighted red and bold; the rest are just bold.
        // <font color> and <b> are legacy HTML tags that Html.fromHtml renders on every API level.
        val html = rules.mapIndexed { index, (title, desc) ->
            val t = getString(title)
            val titleHtml = if (index == 0) "<b><font color=\"#E53935\">$t</font></b>" else "<b>$t</b>"
            "$titleHtml<br>${getString(desc)}"
        }.joinToString("<br><br>")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.support_rules_button)
            .setMessage(fromHtml(html))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun fromHtml(html: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }
}
