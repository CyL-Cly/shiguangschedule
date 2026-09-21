package com.xingheyuzhuan.shiguangschedule

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.xingheyuzhuan.shiguangschedule.navigation.GdouDeepLink
import com.xingheyuzhuan.shiguangschedule.service.GdouSyncNotification

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleGdouIntent(intent)
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGdouIntent(intent)
    }

    private fun handleGdouIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(GdouSyncNotification.EXTRA_OPEN_GDOU, false) == true) {
            GdouDeepLink.requestOpen()
        }
    }
}