package com.gamch1k.lifeos.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives `lifeos://pair` links (P21-06): from the sign-in page in the
 * Custom Tab that "Sign in on this phone" opened, or from a QR code the
 * camera app opened. It only hands the link to [MainActivity], whose Pair
 * screen checks it and asks before it pairs (a link can come from anywhere),
 * and closes. `CLEAR_TOP` closes the Custom Tab that sat on top of
 * MainActivity, the way AppAuth's redirect receiver does.
 */
class PairLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        if (link != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_PAIR)
                    .putExtra(MainActivity.EXTRA_PAIR_LINK, link)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }
}
