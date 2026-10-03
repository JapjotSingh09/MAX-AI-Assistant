package com.max.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import java.util.Locale

/**
 * DEVICE INFORMATION - reading facts, never opening settings.
 *
 * This class exists because of a specific failure: "My battery is at 15%, how
 * long will it last?" used to open Battery Settings. The user's question is
 * about a NUMBER, so the answer has to come from a number source, and this is
 * it. None of the read methods start an Activity.
 *
 * Where Android does not expose a value, the message says so plainly instead
 * of guessing. That honesty is the design rule, not a nicety.
 */
class DeviceInfoAction(private val context: Context) {

    /**
     * The sticky ACTION_BATTERY_CHANGED broadcast.
     *
     * `registerReceiver(null, filter)` is the documented way to read battery
     * state: it needs NO permission and returns the last broadcast instantly,
     * which keeps a battery answer off the critical path.
     */
    private val batteryIntent: Intent? by lazy {
        try {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                null as android.content.BroadcastReceiver?,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Battery level as a percentage, or null if the OS did not report one. */
    fun batteryPercent(): Int? {
        val intent = batteryIntent ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100.0 / scale).toInt().coerceIn(0, 100)
    }

    /**
     * What the battery is doing right now: "charging", "full", "draining", or
     * null when Android does not say. Public because the home screen shows it.
     */
    fun batteryStatusLabel(): String? =
        when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "draining"
            else -> null
        }

    /**
     * "What's my battery percentage?"
     *
     * Answers with the number plus what the phone is currently doing, which is
     * the extra fact a user actually wants at that moment.
     */
    fun readBatteryLevel(): ActionResult {
        val pct = batteryPercent()
            ?: return ActionResult.Failed("Android didn't report your battery level right now.")
        val extra = if (batteryStatusLabel() == "charging") " and it's charging." else "."
        val saver = if (isBatterySaverOn()) " Battery saver is on." else ""
        return ActionResult.Completed("Your battery is at $pct%$extra$saver")
    }

    /**
     * "How long will my battery last?"
     *
     * Android DOES expose an estimate through BatteryManager, but many devices
     * return Long.MIN_VALUE or a meaningless figure (0, or "under a minute") in
     * normal use. MAX reports the OS number only when it passes a plausibility
     * gate, and otherwise SAYS THAT THERE ISN'T ONE.
     *
     * It never invents a number. That is the contract of this method.
     */
    fun readBatteryEstimate(): ActionResult {
        val pct = batteryPercent()
            ?: return ActionResult.Failed("Android didn't report your battery level right now.")
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val remainingMs = try {
            bm?.computeChargeTimeRemaining() ?: Long.MIN_VALUE
        } catch (_: Exception) {
            Long.MIN_VALUE
        }

        // Plausibility gate: documented to return Long.MIN_VALUE when unknown,
        // and devices are known to return 0 or absurd values.
        val usable = remainingMs > 60_000L && remainingMs < 7L * 24 * 60 * 60 * 1000
        if (usable) {
            val mins = (remainingMs / 60_000.0).toLong()
            return ActionResult.Completed(
                "Your battery is at $pct%. Android estimates about ${humanizeMinutes(mins)} remaining."
            )
        }

        val tail = when (batteryStatusLabel()) {
            "charging" -> "You're plugged in, so it's charging."
            "full" -> "It's fully charged."
            else -> "Android doesn't provide a reliable remaining-time estimate on this device."
        }
        val saver = if (isBatterySaverOn()) " Battery saver is on." else ""
        return ActionResult.Completed("Your battery is at $pct%. $tail$saver")
    }

    /** 90 -> "1 hour 30 minutes". Never renders a meaningless "0 minutes". */
    private fun humanizeMinutes(mins: Long): String {
        val h = mins / 60
        val m = mins % 60
        return when {
            h <= 0L -> "$m minutes"
            m == 0L -> if (h == 1L) "1 hour" else "$h hours"
            h == 1L -> "1 hour $m minutes"
            else -> "$h hours $m minutes"
        }
    }

    /** Whether battery saver is currently on. This only READS. */
    fun isBatterySaverOn(): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true
    } catch (_: Exception) {
        false
    }
/**
     * "Turn on battery saver"
     *
     * Android has NO public API for an app to enable power save mode - it is a
     * global user setting. So MAX opens the screen and says exactly that,
     * rather than reporting a change that never happened.
     */
    fun setBatterySaver(state: String?): ActionResult {
        val current = isBatterySaverOn()
        return try {
            context.startActivity(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val alreadyRight = when (state) {
                "on" -> current
                "off" -> !current
                else -> false
            }
            when {
                alreadyRight && state == "on" -> ActionResult.Completed("Battery saver is already on.")
                alreadyRight && state == "off" -> ActionResult.Completed("Battery saver is already off.")
                else -> ActionResult.Prepared(
                    "Android doesn't let apps switch battery saver themselves, so I've opened that setting for you."
                )
            }
        } catch (_: Exception) {
            ActionResult.Failed("This phone has no battery saver settings screen.")
        }
    }

    /** "What's my current Wi-Fi status?" */
    fun readWifiStatus(): ActionResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ActionResult.Failed("I couldn't read your network status.")
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return when {
            caps == null -> ActionResult.Completed("You're not connected to Wi-Fi or mobile data.")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                ActionResult.Completed("Wi-Fi is on and you're connected to ${readSsid() ?: "a network"}.")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                ActionResult.Completed("Wi-Fi is off. You're using mobile data.")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                ActionResult.Completed("You're connected over Ethernet.")
            else -> ActionResult.Completed("You're connected to a network.")
        }
    }

    /**
     * The current SSID.
     *
     * Modern Android returns `<unknown ssid>` without location permission. MAX
     * does NOT ask for location merely to name a Wi-Fi network, so it reports
     * honestly instead of requesting a sensitive permission for a nicety.
     */
    private fun readSsid(): String? = try {
        // `connectionInfo` is deprecated, but `WifiInfo.getSSID()` is only
        // available from API 31. minSdk is 26, so the deprecated accessor is
        // still the only one that works everywhere; the result is checked
        // immediately and the fallback below keeps the answer honest.
        @Suppress("DEPRECATION")
        val legacy = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            ?.let { it as? WifiManager }
            ?.connectionInfo?.ssid?.trim('"')

        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val wifi = context.applicationContext.getSystemService(
                    Context.WIFI_SERVICE
                ) as WifiManager
                @Suppress("DEPRECATION")
                wifi.connectionInfo?.ssid?.trim('"')
            }.getOrNull() ?: legacy
        } else {
            legacy
        }
        ssid?.takeIf { it.isNotBlank() && it != "0" && !it.equals("unknown ssid", true) }
    } catch (_: Exception) {
        null
    }
/** "Is Bluetooth on?" - a READ. Switching it on is a different intent. */
    fun readBluetoothStatus(): ActionResult {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
            ?: return ActionResult.Failed("This phone doesn't report Bluetooth state.")
        return ActionResult.Completed(
            if (adapter.isEnabled) "Bluetooth is on." else "Bluetooth is off."
        )
    }

    /** "What's my phone model?" */
    fun readDeviceInfo(): ActionResult {
        val model = Build.MODEL?.trim().orEmpty()
        val brand = Build.BRAND?.trim().orEmpty()
        val name = if (brand.isNotEmpty() && !model.lowercase().startsWith(brand.lowercase())) {
            "$brand $model"
        } else model
        val shown = name.ifBlank { "this phone" }
        return ActionResult.Completed(
            "This is a $shown running Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})."
        )
    }

    /** "How much storage do I have?" */
    fun readStorage(): ActionResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        if (total <= 0) return ActionResult.Failed("I couldn't read your storage right now.")
        val usedPct = ((total - free) * 100 / total).toInt()
        return ActionResult.Completed(
            "You have ${humanBytes(free)} free of ${humanBytes(total)} - about $usedPct% used."
        )
    }

    /** "How bright is my screen?" - reads the setting; never changes it. */
    fun readDisplayInfo(): ActionResult {
        val brightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            -1
        }
        if (brightness < 0) return ActionResult.Completed("I couldn't read the screen brightness.")
        val pct = (brightness * 100 / 255).coerceIn(0, 100)
        return ActionResult.Completed("Screen brightness is about $pct%.")
    }

    /** "What's my sound like?" - the current ringer mode and volume, as facts. */
    fun readSoundInfo(): ActionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return ActionResult.Failed("I couldn't read your sound settings.")
        val mode = when (am.ringerMode) {
            android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
            android.media.AudioManager.RINGER_MODE_VIBRATE -> "on vibrate"
            else -> "on sound"
        }
        val music = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return ActionResult.Completed(
            "Your phone is $mode, and media volume is about ${music * 100 / max}%."
        )
    }

    /** Human byte formatting, using the MB/GB units people actually judge by. */
    fun humanBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
        else String.format(Locale.ROOT, "%.0f MB", mb)
    }
}