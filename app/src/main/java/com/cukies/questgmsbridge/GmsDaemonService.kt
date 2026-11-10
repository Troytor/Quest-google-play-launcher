package com.cukies.questgmsbridge

import android.accounts.AccountManager
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

    private var boundAction: String = ""

    private val universalBillingAndAuthBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val billingDescriptor = "com.android.vending.billing.IInAppBillingService"

            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(billingDescriptor)
                return true
            }

            return try {
                // 1. Universal Google Play Licensing Service (LVL) Responder
                if (boundAction.contains("ILicensingService")) {
                    reply?.writeNoException()
                    return true
                }

                // 2. Universal Google Play Auth / Games Startup Responder
                if (boundAction.contains("com.google.android.gms")) {
                    reply?.writeNoException()
                    reply?.writeInt(0)
                    return true
                }

                data.enforceInterface(billingDescriptor)

                when (code) {
                    // 1: isBillingSupported(int apiVersion, String packageName, String type)
                    1 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        Log.i(BridgeApp.TAG, "UniversalBilling isBillingSupported(v=$apiVersion, pkg=$pkg, type=$type)")
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
                        Log.i(BridgeApp.TAG, "UniversalBilling getSkuDetails(v=$apiVersion, pkg=$pkg, queried=${skuList.size} SKUs)")

                        val response = buildAndSyncCatalogDetails(pkg, type, skuList)
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
                        Log.i(BridgeApp.TAG, "UniversalBilling getBuyIntent(v=$apiVersion, pkg=$pkg, sku=$sku)")

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
                        Log.i(BridgeApp.TAG, "UniversalBilling getPurchases(v=$apiVersion, pkg=$pkg, type=$type)")

                        val resultBundle = queryAccountEntitlementsForApp(pkg, type)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        resultBundle.writeToParcel(reply!!, 0)
                        true
                    }

                    // 5: consumePurchase(int apiVersion, String packageName, String purchaseToken)
                    5 -> {
                        reply?.writeNoException()
                        reply?.writeInt(0) // BILLING_RESPONSE_RESULT_OK
                        true
                    }

                    // 6 & 8: getPurchaseHistory OR getBuyIntentExtraParams
                    6, 8 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val thirdArg = data.readString() ?: ""
                        val fourthArg = data.readString() ?: "inapp"

                        if (thirdArg == "inapp" || thirdArg == "subs") {
                            Log.i(BridgeApp.TAG, "UniversalBilling getPurchaseHistory(v=$apiVersion, pkg=$pkg, type=$thirdArg)")
                            val resultBundle = queryAccountEntitlementsForApp(pkg, thirdArg)
                            reply?.writeNoException()
                            reply?.writeInt(1)
                            resultBundle.writeToParcel(reply!!, 0)
                        } else {
                            val devPayload = try { data.readString() ?: "" } catch (_: Throwable) { "" }
                            Log.i(BridgeApp.TAG, "UniversalBilling getBuyIntentExtraParams(v=$apiVersion, pkg=$pkg, sku=$thirdArg)")
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
                        Log.i(BridgeApp.TAG, "UniversalBilling isBillingSupportedExtraParams(v=$apiVersion, pkg=$pkg, type=$type)")
                        reply?.writeNoException()
                        reply?.writeInt(0)
                        true
                    }

                    // 9 & 11: getPurchasesExtraParams / getPurchaseHistoryExtraParams (Billing v4-v7)
                    9, 11 -> {
                        val apiVersion = data.readInt()
                        val pkg = data.readString() ?: ""
                        val type = data.readString() ?: "inapp"
                        Log.i(BridgeApp.TAG, "UniversalBilling getPurchasesExtraParams(v=$apiVersion, pkg=$pkg, type=$type)")

                        val resultBundle = queryAccountEntitlementsForApp(pkg, type)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        resultBundle.writeToParcel(reply!!, 0)
                        true
                    }

                    // 10, 17, 18, 19, 20: getSkuDetailsExtraParams / queryProductDetails (Billing v5-v7)
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
                        Log.i(BridgeApp.TAG, "UniversalBilling queryProductDetails(code=$code, v=$apiVersion, pkg=$pkg, count=${skuList.size})")

                        val response = buildAndSyncCatalogDetails(pkg, type, skuList)
                        reply?.writeNoException()
                        reply?.writeInt(1)
                        response.writeToParcel(reply!!, 0)
                        true
                    }

                    // 901, 902, 12..16: acknowledgePurchase / getBillingConfig
                    else -> {
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
                Log.e(BridgeApp.TAG, "UniversalBilling onTransact error (code=$code)", e)
                super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startLightweightForeground()
        initVirtualGmsStorage()
    }

    private fun initVirtualGmsStorage() {
        val gmsDir = File(filesDir, "virtual_gms").apply { mkdirs() }
        val syncFile = File(gmsDir, "account_purchases.json")
        if (!syncFile.exists()) syncFile.writeText("{}")

        val configFile = File(gmsDir, "engine_config.json")
        if (!configFile.exists()) {
            configFile.writeText(JSONObject().apply {
                put("autoSyncCatalogPurchases", true)
                put("linkedGoogleAccount", detectActiveGoogleAccount())
            }.toString(2))
        }
    }

    fun detectActiveGoogleAccount(): String {
        return try {
            val am = AccountManager.get(this)
            val googleAcc = am.accounts.firstOrNull {
                it.type.contains("google", ignoreCase = true) ||
                it.type.contains("microg", ignoreCase = true) ||
                it.name.contains("@")
            }
            if (googleAcc != null) return googleAcc.name

            val configFile = File(filesDir, "virtual_gms/engine_config.json")
            if (configFile.exists()) {
                val saved = JSONObject(configFile.readText()).optString("linkedGoogleAccount", "")
                if (saved.isNotBlank()) return saved
            }
            "Google Play Bridge Account (Active)"
        } catch (_: Throwable) {
            "Google Play Bridge Account (Active)"
        }
    }

    private fun isAutoSyncCatalogEnabled(): Boolean {
        return try {
            val configFile = File(filesDir, "virtual_gms/engine_config.json")
            if (configFile.exists()) {
                JSONObject(configFile.readText()).optBoolean("autoSyncCatalogPurchases", true)
            } else {
                true
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun createBuyIntentBundle(pkg: String, sku: String, type: String, devPayload: String): Bundle {
        val sheetIntent = Intent(this, BillingPurchaseSheetActivity::class.java).apply {
            putExtra("REQUESTED_PKG", pkg)
            putExtra("REQUESTED_SKU", sku)
            putExtra("ITEM_TYPE", type)
            putExtra("DEV_PAYLOAD", devPayload)
            putExtra("GOOGLE_ACCOUNT", detectActiveGoogleAccount())
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
            (pkg + sku).hashCode(),
            sheetIntent,
            piFlags
        )
        return Bundle().apply {
            putInt("RESPONSE_CODE", 0)
            putParcelable("BUY_INTENT", pendingIntent)
        }
    }

    /**
     * Dynamically responds to any app's SKU catalog query and records the discovered SKUs.
     * If Auto-Sync Account Entitlements is enabled, it automatically binds the app's queried
     * non-consumable entitlements to the active Google Account profile so getPurchases() restores them seamlessly.
     */
    private fun buildAndSyncCatalogDetails(pkg: String, type: String, skus: List<String>): Bundle {
        recordDiscoveredCatalog(pkg, skus)

        if (isAutoSyncCatalogEnabled()) {
            syncCatalogSkusToAccount(pkg, skus)
        }

        val detailsList = ArrayList<String>()
        for (sku in skus) {
            val cleanTitle = sku.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
            val json = JSONObject().apply {
                put("productId", sku)
                put("type", type)
                put("price", "$0.99")
                put("price_amount_micros", 990000L)
                put("price_currency_code", "USD")
                put("title", cleanTitle)
                put("name", cleanTitle)
                put("description", "Google Play Account Synced Entitlement ($sku)")
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

    private fun recordDiscoveredCatalog(pkg: String, skus: List<String>) {
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

    private fun syncCatalogSkusToAccount(pkg: String, skus: List<String>) {
        if (pkg.isBlank() || skus.isEmpty()) return
        try {
            val file = File(filesDir, "virtual_gms/account_purchases.json")
            val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val arr = root.optJSONArray(pkg) ?: JSONArray()
            val existingSkus = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                existingSkus.add(arr.getJSONObject(i).optString("productId"))
            }

            val now = System.currentTimeMillis()
            for (sku in skus) {
                if (sku !in existingSkus) {
                    arr.put(JSONObject().apply {
                        put("productId", sku)
                        put("orderId", "GPA.3391-SYNC-${(pkg + sku).hashCode().and(0x7FFFFFFF)}")
                        put("purchaseTime", now - 3600000L)
                        put("purchaseToken", "gms_account_sync_${pkg}_${sku}")
                    })
                }
            }
            root.put(pkg, arr)
            file.writeText(root.toString(2))
        } catch (_: Throwable) {}
    }

    private fun queryAccountEntitlementsForApp(packageName: String, type: String): Bundle {
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
                        val token = entry.optString("purchaseToken", "gms_account_sync_${packageName}_${sku}")
                        val purchaseJson = JSONObject().apply {
                            put("orderId", entry.optString("orderId", "GPA.3391-8842-9910-QUEST3"))
                            put("packageName", packageName)
                            put("productId", sku)
                            put("purchaseTime", now)
                            put("purchaseState", 0) // 0 = PURCHASED
                            put("purchaseToken", token)
                            put("quantity", 1)
                            put("acknowledged", true)
                            put("autoRenewing", type == "subs")
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
        Log.i(BridgeApp.TAG, "Universal GMS Host Bound: action=$boundAction")
        return universalBillingAndAuthBinder
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
                .setContentText("Universal Google Play Services & Account Entitlement Sync")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification()
        }
        startForeground(1001, notification)
    }
}
