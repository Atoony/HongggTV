package com.hgtv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hgtv.app.ui.AppRoot
import com.hgtv.app.ui.theme.HgtvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HgtvTheme {
                AppRoot()
            }
        }
    }
}
