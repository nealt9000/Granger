package app.granger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.granger.theme.GrangerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GrangerApp).container
        setContent {
            GrangerTheme {
                GrangerNavHost(container)
            }
        }
    }
}
