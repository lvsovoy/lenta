package com.example.lenta

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.lenta.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.drawerLayout?.setStatusBarBackgroundColor(Color.TRANSPARENT)
        binding.drawerLayout?.setStatusBarBackground(null)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        val navController = navHostFragment?.navController ?: return

        binding.navView?.setupWithNavController(navController)
        binding.appBarMain.contentMain.bottomNavView?.setupWithNavController(navController)
    }
}
