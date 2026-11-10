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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GmsDaemonService : Service() {

    companion object {
        val POLYTOPIA_TRIBE_SKUS = listOf(
            "kickoo", "hoodrick", "luxidoor", "vengir", "zebasi",
            "ai-mo", "aimo", "quali", "quetzali", "yadakk",
            "aquarion", "elyrion", "polaris", "cymanti",
            "tribe_kickoo", "tribe_hoodrick", "tribe_luxidoor", "tribe_vengir",
            "tribe_zebasi", "tribe_aimo", "tribe_quetzali", "tribe_yadakk",
            "tribe_aquarion", "tribe_elyrion", "tribe_polaris", "tribe_cymanti"
        )
    }

    private var boundAction: String = ""

    private val billingAndLvlBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val billingDescriptor = "com.android.vending.billing.IInAppBillingService"

            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(billingDescriptor)
                return true
            }

            return try {
                if (boundAction.contains("ILicensingService")) {
                    reply?.writeNoException()
                    return true
                }

                data.enforceInterface(billingDescriptor)

                when (code) {
                    // 1: isBillingSupported(int apiVersion, String packageName, String type)
                    1 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        Log.i(BridgeApp.TAG, "Billing isBillingSupported(v=$apiVersion, pkg=$pkg, type=$type) -> OK")
                        reply?.writeNoException()
                        reply?.writeInt(0) // BILLING_RESPONSE_RESULT_OK
                        true
                    }

                    // 2: getSkuDetails(int apiVersion, String packageName, String type, Bundle skusBundle)
                    2 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        val skusBundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else Bundle()
                        val skuList = skusBundle.getStringArrayList("ITEM_ID_LIST") ?: arrayListOf()
                        Log.i(BridgeApp.TAG, "Billing getSkuDetails(v=$apiVersion, pkg=$pkg, count=${skuList.size})")

                        val response = buildSkuDetailsBundle(pkg, type, skuList)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        response.writeToParcel(reply!!, 0)
                        true
                    }

                    // 3: getBuyIntent(int apiVersion, String packageName, String sku, String type, String developerPayload)
                    3 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val sku = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        val devPayload = data.readString() ?: ""
                        Log.i(BridgeApp.TAG, "Billing getBuyIntent(v=$apiVersion, pkg=$pkg, sku=$sku)")

                        val buyBundle = createBuyIntentBundle(pkg, sku, type, devPayload)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        buyBundle.writeToParcel(reply!!, 0)
                        true
                    }

                    // 4: getPurchases(int apiVersion, String packageName, String type, String continuationToken)
                    4 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        Log.i(BridgeApp.TAG, "Billing getPurchases(v=$apiVersion, pkg=$pkg, type=$type)")

                        val resultBundle = querySyncedAccountPurchases(pkg)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        resultBundle.writeToParcel(reply!!, 0)
                        true
                    }

                    // 5: consumePurchase(int apiVersion, String packageName, String purchaseToken)
                    5 -> {
                        reply?.writeNoException()
                        reply?.writeInt(0) // RESULT_OK
                        true
                    }

                    // 6 & 8: getPurchaseHistory OR getBuyIntentExtraParams
                    6, 8 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val thirdArg = data.readString() ?: ""
                        val fourthArg = data.readString() ?: "inapp"

                        if (thirdArg == "inapp" || thirdArg == "subs") {
                            Log.i(BridgeApp.TAG, "Billing getPurchaseHistory(v=$apiVersion, pkg=$pkg, type=$thirdArg)")
                            val resultBundle = querySyncedAccountPurchases(pkg)
                            reply?.writeNoException()
                            reply?.writeInt(1)
                            resultBundle.writeToParcel(reply!!, 0)
                        } else {
                            val devPayload = try { data.readString() ?: "" } catch (_: Throwable) { "" }
                            Log.i(BridgeApp.TAG, "Billing getBuyIntentExtraParams(v=$apiVersion, pkg=$pkg, sku=$thirdArg)")
                            val buyBundle = createBuyIntentBundle(pkg, thirdArg, fourthArg, devPayload)
                            reply?.writeNoException()
                            reply?.writeInt(1)
                            buyBundle.writeToParcel(reply!!, 0)
                        }
                        true
                    }

                    // 7: isBillingSupportedExtraParams(int apiVersion, String packageName, String type, Bundle extraParams)
                    7 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        Log.i(BridgeApp.TAG, "Billing isBillingSupportedExtraParams(v=$apiVersion, pkg=$pkg, type=$type) -> OK")
                        reply?.writeNoException()
                        reply?.writeInt(0) // BILLING_RESPONSE_RESULT_OK
                        true
                    }

                    // 9 & 11: getPurchasesExtraParams / getPurchaseHistoryExtraParams
                    9, 11 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        Log.i(BridgeApp.TAG, "Billing getPurchasesExtraParams(v=$apiVersion, pkg=$pkg, type=$type)")

                        val resultBundle = querySyncedAccountPurchases(pkg)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        resultBundle.writeToParcel(reply!!, 0)
                        true
                    }

                    // 10, 17, 18, 19, 20: getSkuDetailsExtraParams / queryProductDetails
                    10, 17, 18, 19, 20 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        val skusBundle = if (data.dataAvail() > 0 && data.readInt() != 0) {
                            Bundle.CREATOR.createFromParcel(data)
                        } else {
                            Bundle()
                        }
                        val skuList = skusBundle.getStringArrayList("ITEM_ID_LIST") ?: arrayListOf()
                        Log.i(BridgeApp.TAG, "Billing getSkuDetailsExtraParams(code=$code, v=$apiVersion, pkg=$pkg, skus=$skuList)")

                        val response = buildSkuDetailsBundle(pkg, type, skuList)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        response.writeToParcel(reply!!, 0)
                        true
                    }

                    else -> {
                        Log.d(BridgeApp.TAG, "Billing generic OK response for transaction code=$code")
                        val okBundle = Bundle().apply {
                            putInt("RESPONSE_CODE", 0)
                            putString("BILLING_CONFIG", "{\"countryCode\":\"US\"}")
                        }
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        okBundle.writeToParcel(reply!!, 0)
                        true
                    }
                }
            } catch (e: Throwable) {
                Log.e(BridgeApp.TAG, "Billing onTransact error on code=$code", e)
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
        val syncFile = File(gmsDir, "account_purchases.json")
        if (!syncFile.exists()) {
            syncFile.writeText("{}")
        }
    }

    private fun createBuyIntentBundle(pkg: String, sku: String, type: String, devPayload: String): Bundle {
        val sheetIntent = Intent(this, BillingPurchaseSheetActivity::class.java).apply {
            putExtra("REQUESTED_PKG", pkg)
            putExtra("REQUESTED_SKU", sku)
            putExtra("ITEM_TYPE", type)
            putExtra("DEV_PAYLOAD", devPayload)
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            sku.hashCode(),
            sheetIntent,
            piFlags
        )
        return Bundle().apply {
            putInt("RESPONSE_CODE", 0)
            putParcelable("BUY_INTENT", pendingIntent)
        }
    }

    private fun buildSkuDetailsBundle(pkg: String, type: String, skus: List<String>): Bundle {
        recordDiscoveredSkus(pkg, skus)

        val detailsList = ArrayList<String>()
        for (sku in skus) {
            val cleanTitle = sku.replaceFirstChar { it.uppercase() }
            val json = JSONObject().apply {
                put("productId", sku)
                put("type", type)
                put("price", "$0.99")
                put("price_amount_micros", 990000L)
                put("price_currency_code", "USD")
                put("title", cleanTitle)
                put("name", cleanTitle)
                put("description", "Google Play Synced Item ($sku)")
                put("skuDetailsToken", "AEuhp4I_${sku}_token")
                put("oneTimePurchaseOfferDetails", JSONObject().apply {
                    put("formattedPrice", "$0.99")
                    put("priceAmountMicros", 990000L)
                    put("priceCurrencyCode", "USD")
                    put("offerToken", "offer_token_$sku")
                })
            }
            detailsList.add(json.toString())
        }
        return Bundle().apply {
            putInt("RESPONSE_CODE", 0)
            putStringArrayList("DETAILS_LIST", detailsList)
        }
    }

    private fun recordDiscoveredSkus(pkg: String, skus: List<String>) {
        if (pkg.isBlank() || skus.isEmpty()) return
        try {
            val file = File(filesDir, "virtual_gms/discovered_skus.json")
            val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val existing = root.optJSONArray(pkg) ?: JSONArray()
            val set = mutableSetOf<String>()
            for (i in 0 until existing.length()) set.add(existing.getString(i))
            skus.forEach { set.add(it) }
            val updated = JSONArray()
            set.forEach { updated.put(it) }
            root.put(pkg, updated)
            file.writeText(root.toString(2))
        } catch (_: Throwable) {}
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
                        val now = entry.optLong("purchaseTime", System.currentTimeMillis() - 86400000L)
                        val token = entry.optString("purchaseToken", "quest3_gms_token_${packageName}_${sku}")
                        val purchaseJson = JSONObject().apply {
                            put("orderId", entry.optString("orderId", "GPA.3391-8842-9910-QUEST3"))
                            put("packageName", packageName)
                            put("productId", sku)
                            put("purchaseTime", now)
                            put("purchaseState", 0)
                            put("purchaseToken", token)
                            put("quantity", 1)
                            put("acknowledged", true)
                            put("autoRenewing", false)
                        }
                        itemList.add(sku)
                        dataList.add(purchaseJson.toString())
                        sigList.add(entry.optString("signature", "GooglePlaySyncedSignature_${sku.hashCode()}"))
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
        boundAction = intent?.action ?: ""
        Log.i(BridgeApp.TAG, "Client bound to GmsDaemonService (com.android.vending): action=$boundAction")
        return billingAndLvlBinder
    }

    private fun startLightweightForeground() {
        val channelId = "spatial_gms_os27_daemon"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SpatialGMS OS 27 Play Store Host",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("SpatialGMS Play Store Host Active")
                .setContentText("Serving com.android.vending In-App Billing & Account Sync")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification()
        }
        startForeground(1001, notification)
    }
}
