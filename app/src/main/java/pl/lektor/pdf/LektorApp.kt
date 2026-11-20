package pl.lektor.pdf

import android.app.Application
import com.google.android.material.color.DynamicColors

class LektorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Kolory z tapety na Androidzie 12+
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
