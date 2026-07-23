package com.pricetranslator.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RateRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            ExchangeRateRepository(applicationContext).fetch("USD", "RUB")
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
