package app.veil.camera

import android.content.Context
import androidx.annotation.StringRes

/**
 * A snackbar message the view model raises. It carries a resource id rather
 * than text so it is rendered in the language the UI is currently showing.
 */
data class UiMessage(
    @StringRes val text: Int,
    val args: List<String> = emptyList(),
    /** A saved video offers a share action next to the message. */
    val offerVideoShare: Boolean = false,
) {
    fun resolve(context: Context): String = context.getString(text, *args.toTypedArray())
}
