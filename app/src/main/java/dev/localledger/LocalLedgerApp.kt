package dev.localledger

import android.app.Application
import dev.localledger.data.LedgerRepository

class LocalLedgerApp : Application() {
    val repository: LedgerRepository by lazy { LedgerRepository(this) }
}
