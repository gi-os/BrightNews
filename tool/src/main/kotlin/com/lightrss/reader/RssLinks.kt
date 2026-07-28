package com.lightrss.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Hands a URL to whatever app on the phone handles web links.
 *
 * Reader mode cannot clear a bot check or sign in for you, so when a publisher puts one in the
 * way the only useful move is to open the page somewhere that can. Returns false when nothing on
 * the phone will take the link, which on a Light Phone is a real possibility.
 */
@Composable
fun rememberLinkOpener(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { url: String ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        }
    }
}
