package com.pricetranslator.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleRateRefresh()
        setContent { MaterialTheme { PriceTranslatorApp() } }
    }

    private fun scheduleRateRefresh() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = PeriodicWorkRequestBuilder<RateRefreshWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-rate-refresh", ExistingPeriodicWorkPolicy.UPDATE, work)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceTranslatorApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ExchangeRateRepository(context) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var processed by remember { mutableStateOf<Bitmap?>(null) }
    var rate by remember { mutableStateOf(repository.cached("USD", "RUB")) }
    var status by remember { mutableStateOf("Choose a price photo to begin") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }
                .onSuccess { original = it; processed = null; status = "Photo loaded" }
                .onFailure { status = it.message ?: "Could not open photo" }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Price Translator") }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("USD → RUB", style = MaterialTheme.typography.titleMedium)
            Text(rate?.let { "Online rate: 1 USD = ${"%.4f".format(it.value)} RUB · ${it.date}" } ?: "Online rate has not been downloaded yet")
            Button(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Choose photo") }

            original?.let { bitmap ->
                Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
                Button(enabled = !busy, onClick = {
                    busy = true
                    status = "Downloading rate…"
                    scope.launch {
                        val activeRate = withContext(Dispatchers.IO) {
                            runCatching { repository.fetch("USD", "RUB") }.getOrElse { repository.cached("USD", "RUB") }
                        }
                        if (activeRate == null) { busy = false; status = "No internet and no saved rate"; return@launch }
                        rate = activeRate
                        status = "Recognizing prices…"
                        PriceImageProcessor.process(bitmap, activeRate,
                            onSuccess = { image, count -> processed = image; busy = false; status = if (count > 0) "$count price(s) replaced" else "No USD prices found" },
                            onFailure = { busy = false; status = it.message ?: "Recognition failed" })
                    }
                }) { Text("Translate prices") }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status)
            processed?.let { bitmap ->
                HorizontalDivider()
                Text("Result", style = MaterialTheme.typography.titleMedium)
                Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit)
                Button(onClick = {
                    runCatching { saveBitmap(context, bitmap) }
                        .onSuccess { status = "Saved to Pictures/Price Translator" }
                        .onFailure { status = it.message ?: "Could not save image" }
                }) { Text("Save image") }
            }
        }
    }
}

private fun saveBitmap(context: android.content.Context, bitmap: Bitmap) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "price_translator_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Price Translator")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create image")
    context.contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) } ?: error("Unable to open output stream")
}
