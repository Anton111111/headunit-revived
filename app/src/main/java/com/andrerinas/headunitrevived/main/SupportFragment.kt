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

    private fun fromHtml(html: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }
}
