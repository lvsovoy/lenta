package me.lesovoy.lenta

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import me.lesovoy.lenta.databinding.ActivityMainBinding
import me.lesovoy.lenta.ui.viewer.MediaViewerActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val drawerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (handleViewIntent(intent)) {
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.drawerLayout.setStatusBarBackgroundColor(Color.TRANSPARENT)
        binding.drawerLayout.setStatusBarBackground(null)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        val navController = navHostFragment?.navController ?: return

        binding.navView.setupWithNavController(navController)
        binding.appBarMain.contentMain.bottomNavView.setupWithNavController(navController)

        // Ensure drawer closes when a destination is selected
        navController.addOnDestinationChangedListener { _, _, _ ->
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        // Close drawer on back press when it is open
        onBackPressedDispatcher.addCallback(this, drawerBackCallback)
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback.isEnabled = false
            }

            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                drawerBackCallback.isEnabled = slideOffset > 0.05f
            }
        })
    }

    fun openDrawer() {
        if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    fun closeDrawer() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    fun toggleDrawer() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?): Boolean {
        if (intent != null && intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val scheme = intent.data?.scheme?.lowercase()
            if (scheme == "me.lesovoy.lenta" || scheme == "lenta") {
                // OAuth callback deep link or internal scheme - do not route to MediaViewerActivity
                return false
            }
            val viewerIntent = Intent(intent).apply {
                setClass(this@MainActivity, MediaViewerActivity::class.java)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(viewerIntent)
            return true
        }
        return false
    }
}
