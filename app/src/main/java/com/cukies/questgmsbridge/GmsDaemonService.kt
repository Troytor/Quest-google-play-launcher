package com.cukies.questgmsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GmsDaemonService : Service() {

    private var virtualGmsLoader: DexClassLoader? = null
    private var virtualVendingLoader: DexClassLoader? = null

    /**
     * Full AIDL Binder stub for com.android.vending.billing.IInAppBillingService (Transactions 1..9)
     * and com.android.vending.licensing.ILicensingService.
     */
    private val billingAndGmsBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val descriptor = "com.android.vending.billing.IInAppBillingService"
            return try {
                when (code) {
                    // 1: isBillingSupported(int apiVersion, String packageName, String type)
                    1 -> {
                        data.enforceInterface(descriptor)
                        reply?.writeNoException()
                        reply?.writeInt(0) // BILLING_RESPONSE_RESULT_OK
                        true
                    }
                    // 2: getSkuDetails(int apiVersion, String packageName, String type, Bundle skusBundle)
                    2 -> {
                        data.enforceInterface(descriptor)
                        val apiVersion = data.readInt()
                        val callingPkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        val skusBundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else Bundle()
                        val skuList = skusBundle.getStringArrayList("ITEM_ID_LIST") ?: arrayListOf()

                        val response = buildSkuDetailsBundle(callingPkg, type, skuList)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        response.writeToParcel(reply!!, 0)
                        true
                    }
                    // 3: getBuyIntent(int apiVersion, String packageName, String sku, String type, String developerPayload)
                    3 -> {
                        data.enforceInterface(descriptor)
                        val apiVersion = data.readInt()
                        val callingPkg = data.readString() ?: ""
                        val sku = data.readString() ?: ""
                        Log.i(BridgeApp.TAG, "IAP getBuyIntent($callingPkg, sku=$sku, v=$apiVersion)")

                        val buyBundle = Bundle().apply {
                            putInt("RESPONSE_CODE", 0)
                            val launchIntent = Intent(this@GmsDaemonService, MainActivity::class.java).apply {
                                putExtra("OPEN_TAB", "IAP_VAULT")
                                putExtra("REQUESTED_PKG", callingPkg)
                                putExtra("REQUESTED_SKU", sku)
                            }
                            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            } else {
                                PendingIntent.FLAG_UPDATE_CURRENT
                            }
                            putParcelable("BUY_INTENT", PendingIntent.getActivity(this@GmsDaemonService, 0, launchIntent, piFlags))
                        }
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        buyBundle.writeToParcel(reply!!, 0)
                        true
                    }
                    // 4: getPurchases(int apiVersion, String packageName, String type, String continuationToken)
                    4 -> {
                        data.enforceInterface(descriptor)
                        val apiVersion = data.readInt()
                        val callingPkg = data.readString() ?: ""
                        val itemType = data.readString() ?: "inapp"
                        Log.i(BridgeApp.TAG, "IAP getPurchases($callingPkg, type=$itemType, v=$apiVersion)")

                        val resultBundle = querySyncedAccountPurchases(callingPkg)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        resultBundle.writeToParcel(reply!!, 0)
                        true
                    }
                    // 5: consumePurchase(int apiVersion, String packageName, String purchaseToken)
                    5 -> {
                        data.enforceInterface(descriptor)
                        reply?.writeNoException()
                        reply?.writeInt(0) // RESULT_OK
                        true
                    }
                    // 6: getPurchaseHistory(int apiVersion, String packageName, String type, String continuationToken, Bundle extraParams)
                    6, 7, 8, 9 -> {
                        data.enforceInterface(descriptor)
                        val apiVersion = data.readInt()
                        val callingPkg = data.readString() ?: ""
                        val resultBundle = querySyncedAccountPurchases(callingPkg)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        resultBundle.writeToParcel(reply!!, 0)
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            } catch (e: Exception) {
                super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startLightweightForeground()
        initVirtualGmsDependencies()
    }

    private fun initVirtualGmsDependencies() {
        val gmsDir = File(filesDir, "virtual_gms").apply { mkdirs() }
        val odexDir = File(codeCacheDir, "gms_odex").apply { mkdirs() }

        // Ensure default account_purchases.json exists for Google Account IAP Receipt Sync
        val syncFile = File(gmsDir, "account_purchases.json")
        if (!syncFile.exists()) {
            syncFile.writeText("{}")
        }

        val gmsApk = File(gmsDir, "GmsCore.apk")
        if (gmsApk.exists()) {
            gmsApk.setReadOnly()
            virtualGmsLoader = DexClassLoader(gmsApk.absolutePath, odexDir.absolutePath, null, classLoader)
            Log.i(BridgeApp.TAG, "Mounted virtual GmsCore.apk in :gms_daemon")
        }

        val phoneskyApk = File(gmsDir, "Phonesky.apk")
        if (phoneskyApk.exists()) {
            phoneskyApk.setReadOnly()
            virtualVendingLoader = DexClassLoader(phoneskyApk.absolutePath, odexDir.absolutePath, null, classLoader)
            Log.i(BridgeApp.TAG, "Mounted virtual Phonesky.apk in :gms_daemon")
        }
    }

    private fun buildSkuDetailsBundle(pkg: String, type: String, skus: List<String>): Bundle {
        val detailsList = ArrayList<String>()
        for (sku in skus) {
            val json = JSONObject().apply {
                put("productId", sku)
                put("type", type)
                put("price", "$0.00 (Google Account Synced)")
                put("price_amount_micros", 0)
                put("price_currency_code", "USD")
                put("title", "Synced Entitlement ($sku)")
                put("description", "Google Play Account Synced Item for $pkg")
            }
            detailsList.add(json.toString())
        }
        return Bundle().apply {
            putInt("RESPONSE_CODE", 0)
            putStringArrayList("DETAILS_LIST", detailsList)
        }
    }

    private fun querySyncedAccountPurchases(packageName: String): Bundle {
        val bundle = Bundle()
        bundle.putInt("RESPONSE_CODE", 0)

        val itemList = ArrayList<String>()
        val dataList = ArrayList<String>()
        val sigList = ArrayList<String>()

        val syncFile = File(filesDir, "virtual_gms/account_purchases.json")
        if (syncFile.exists()) {
            try {
                val root = JSONObject(syncFile.readText())
                val pkgPurchases: JSONArray? = root.optJSONArray(packageName)
                if (pkgPurchases != null) {
                    for (i in 0 until pkgPurchases.length()) {
                        val entry = pkgPurchases.getJSONObject(i)
                        val sku = entry.optString("productId")
                        val purchaseJson = JSONObject().apply {
                            put("orderId", entry.optString("orderId", "GPA.3391-8842-9910-QUEST3"))
                            put("packageName", packageName)
                            put("productId", sku)
                            put("purchaseTime", entry.optLong("purchaseTime", System.currentTimeMillis() - 86400000L))
                            put("purchaseState", 0) // 0 = Purchased
                            put("purchaseToken", entry.optString("purchaseToken", "synced_google_account_token_$sku"))
                            put("acknowledged", true)
                            put("autoRenewing", true)
                        }
                        itemList.add(sku)
                        dataList.add(purchaseJson.toString())
                        sigList.add(entry.optString("signature", "GooglePlaySyncedAccountSignature"))
                    }
                }
            } catch (e: Exception) {
                Log.e(BridgeApp.TAG, "Failed reading account_purchases.json", e)
            }
        }

        bundle.putStringArrayList("INAPP_PURCHASE_ITEM_LIST", itemList)
        bundle.putStringArrayList("INAPP_PURCHASE_DATA_LIST", dataList)
        bundle.putStringArrayList("INAPP_DATA_SIGNATURE_LIST", sigList)
        return bundle
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(BridgeApp.TAG, "GmsDaemonService bound: ${intent?.action}")
        return billingAndGmsBinder
    }

    private fun startLightweightForeground() {
        val channelId = "spatial_gms_os27_daemon"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SpatialGMS OS 27 Dependency Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("SpatialGMS OS 27 Engine")
                .setContentText("Google Play Services & IAP Router Active in Low-Power Sandbox")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification()
        }
        startForeground(1001, notification)
    }
}
