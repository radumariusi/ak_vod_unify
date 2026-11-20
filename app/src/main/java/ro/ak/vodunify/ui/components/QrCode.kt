package ro.ak.vodunify.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Cod QR pentru un link (ex. pagina de activare Trakt), ca să-l scanezi cu telefonul de pe TV. */
@Composable
fun QrCode(content: String, size: Dp = 160.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { runCatching { qrBitmap(content, 256) }.getOrNull() } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Cod QR pentru $content",
        filterQuality = FilterQuality.None,
        modifier = modifier
            .background(Color.White)
            .padding(8.dp)
            .size(size),
    )
}

private fun qrBitmap(content: String, px: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
