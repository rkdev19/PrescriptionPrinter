package com.rkstudio19.prescriptionprinter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Finds printers on whatever Wi-Fi network the phone is currently on
 * (the Jio dongle's network, in the client's setup) using mDNS/NSD - the
 * same discovery mechanism Epson's own iPrint app uses to find printers
 * without anyone typing an IP address.
 *
 * Runs continuously in the background once started (from
 * PrintQueueForegroundService) and keeps a live map of printer name ->
 * IP:port, automatically updated if the network hands out a new IP after
 * a reboot or reconnect - this is what makes the earlier hardcoded-IP
 * approach unnecessary and safe against IP drift on a dongle network.
 */
object PrinterDiscovery {

    private const val TAG = "PrinterDiscovery"
    private const val SERVICE_TYPE = "_ipp._tcp."

    data class DiscoveredPrinter(val name: String, val host: String, val port: Int)

    private val discoveredPrinters = mutableMapOf<String, DiscoveredPrinter>() // key = lowercase service name
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true

        // mDNS packets are multicast - without this lock some Android
        // versions/OEMs silently drop them, especially over Wi-Fi.
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("prescriptionPrinterDiscovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Found service: ${service.serviceName}")
                resolveService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                discoveredPrinters.remove(service.serviceName.lowercase())
                Log.d(TAG, "Lost service: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                started = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices failed: ${e.message}")
            started = false
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val printer = DiscoveredPrinter(serviceInfo.serviceName, host, serviceInfo.port)
                discoveredPrinters[serviceInfo.serviceName.lowercase()] = printer
                Log.d(TAG, "Resolved ${printer.name} -> ${printer.host}:${printer.port}")
            }
        })
    }

    /** Returns the first discovered printer whose advertised name contains [nameHint] (e.g. "epson", "canon"). */
    @Synchronized
    fun findPrinter(nameHint: String): DiscoveredPrinter? =
        discoveredPrinters.values.firstOrNull { it.name.lowercase().contains(nameHint.lowercase()) }

    @Synchronized
    fun allDiscovered(): List<DiscoveredPrinter> = discoveredPrinters.values.toList()

    @Synchronized
    fun stop(context: Context) {
        if (!started) return
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery failed: ${e.message}")
        }
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "multicastLock release failed: ${e.message}")
        }
        started = false
    }
}
