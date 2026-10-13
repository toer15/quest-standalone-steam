package com.toer15.queststeam

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.toer15.queststeam.launcher.GameLauncher
import com.toer15.queststeam.steam.OwnedGame
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.steam.SteamApi
import kotlinx.coroutines.launch

/** Lists the owned library, VR titles first and highlighted. Tap = try to launch. */
class LibraryActivity : AppCompatActivity() {

    private val games = mutableListOf<OwnedGame>()
    private lateinit var adapter: GameAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val status = TextView(this).apply { textSize = 20f; text = "Loading library…" }
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@LibraryActivity) }
        adapter = GameAdapter(games) { onGameClicked(it) }
        list.adapter = adapter
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val steamId = SessionStore(this).steamId ?: return finish()
        if (BuildConfig.STEAM_API_KEY.isBlank()) {
            status.text = "Missing STEAM_API_KEY in local.properties (see README)"
            return
        }
        lifecycleScope.launch {
            try {
                val owned = SteamApi.getOwnedGames(BuildConfig.STEAM_API_KEY, steamId)
                status.text = "${owned.size} games — checking which are VR…"
                SteamApi.tagVrGames(owned) // fills isVr via store appdetails
                games.clear()
                games.addAll(owned.sortedWith(compareByDescending<OwnedGame> { it.isVr }.thenBy { it.name.lowercase() }))
                adapter.notifyDataSetChanged()
                status.text = "${owned.size} games, ${owned.count { it.isVr }} VR"
            } catch (e: Exception) {
                status.text = "Error: ${e.message}\n(Is your Steam profile/game details set to public?)"
            }
        }
    }

    private fun onGameClicked(g: OwnedGame) {
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setMessage("Try to run this standalone via Box64 + Wine?\nThis is an experiment and will very likely fail for VR titles.")
            .setPositiveButton("Try it") { _, _ ->
                val result = GameLauncher(this).launch(g.appId, g.name)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class GameAdapter(val items: List<OwnedGame>, val onClick: (OwnedGame) -> Unit) :
        RecyclerView.Adapter<GameAdapter.VH>() {
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(TextView(p.context).apply { textSize = 18f; setPadding(16, 24, 16, 24) })
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val g = items[i]
            h.tv.text = (if (g.isVr) "[VR] " else "") + g.name + "  (${g.playtimeMinutes / 60}h)"
            h.tv.setTextColor(if (g.isVr) Color.rgb(102, 192, 244) else Color.GRAY)
            h.tv.setOnClickListener { onClick(g) }
        }
    }
}
