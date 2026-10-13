package com.toer15.queststeam

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.toer15.queststeam.steam.SessionStore

/** Routes to login or library depending on whether we have a SteamID saved. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (SessionStore(this).steamId == null) LoginActivity::class.java else LibraryActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
