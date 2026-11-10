package com.cukies.questgmsbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BillingPurchaseSheetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callingPkg = intent.getStringExtra("REQUESTED_PKG")
            ?: callingPackage
            ?: "unknown.package"
        val requestedSku = intent.getStringExtra("REQUESTED_SKU") ?: "item_entitlement"
        val itemType = intent.getStringExtra("ITEM_TYPE") ?: "inapp"
        val devPayload = intent.getStringExtra("DEV_PAYLOAD") ?: ""
        val activeAccount = intent.getStringExtra("GOOGLE_ACCOUNT") ?: "Google Play Account"

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC05070C))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(30.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF161C2E), Color(0xFF0D111C))
                                )
                            )
                            .border(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f))
                                ),
                                RoundedCornerShape(30.dp)
                            )
                            .padding(28.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF30D158))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "GOOGLE PLAY BILLING • $activeAccount",
                                        color = Color(0xFF64D2FF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(onClick = { cancelPurchase() }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = Color.White.copy(alpha = 0.7f))
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(Color(0xFF0A84FF).copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.ShoppingBag,
                                        contentDescription = null,
                                        tint = Color(0xFF64D2FF),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = "Google Play Entitlement Verification",
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = callingPkg,
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.Black.copy(alpha = 0.35f))
                                    .padding(16.dp)
                            ) {
                                SheetRow("Product SKU ID", requestedSku)
                                SheetRow("Entitlement Type", itemType.uppercase())
                                SheetRow("Google Account", activeAccount)
                            }

                            Spacer(modifier = Modifier.height(22.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { cancelPurchase() },
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                ) {
                                    Text("Cancel", color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        completeAndDeliverPurchase(
                                            pkg = callingPkg,
                                            sku = requestedSku,
                                            devPayload = devPayload
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .height(50.dp)
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Authorize & Sync",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun completeAndDeliverPurchase(pkg: String, sku: String, devPayload: String) {
        val now = System.currentTimeMillis()
        val token = "gms_account_sync_${pkg}_${sku}"
        val orderId = "GPA.3391-8842-9910-${now % 100000}"

        val purchaseJson = JSONObject().apply {
            put("orderId", orderId)
            put("packageName", pkg)
            put("productId", sku)
            put("purchaseTime", now)
            put("purchaseState", 0)
            put("developerPayload", devPayload)
            put("purchaseToken", token)
            put("quantity", 1)
            put("acknowledged", true)
            put("autoRenewing", false)
        }
        val signature = "GooglePlaySyncedSignature_${sku.hashCode()}"

        try {
            val gmsDir = File(filesDir, "virtual_gms").apply { mkdirs() }
            val file = File(gmsDir, "account_purchases.json")
            val root = if (file.exists()) {
                try { JSONObject(file.readText()) } catch (_: Throwable) { JSONObject() }
            } else {
                JSONObject()
            }
            val arr = root.optJSONArray(pkg) ?: JSONArray()
            var exists = false
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optString("productId") == sku) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                arr.put(JSONObject().apply {
                    put("productId", sku)
                    put("orderId", orderId)
                    put("purchaseTime", now)
                    put("purchaseToken", token)
                    put("signature", signature)
                })
                root.put(pkg, arr)
                file.writeText(root.toString(2))
            }
        } catch (_: Throwable) {}

        val resultIntent = Intent().apply {
            putExtra("RESPONSE_CODE", 0)
            putExtra("INAPP_PURCHASE_DATA", purchaseJson.toString())
            putExtra("INAPP_DATA_SIGNATURE", signature)
            putStringArrayListExtra("INAPP_PURCHASE_ITEM_LIST", arrayListOf(sku))
            putStringArrayListExtra("INAPP_PURCHASE_DATA_LIST", arrayListOf(purchaseJson.toString()))
            putStringArrayListExtra("INAPP_DATA_SIGNATURE_LIST", arrayListOf(signature))
        }

        try {
            val broadcast = Intent("com.android.vending.billing.PURCHASES_UPDATED").apply {
                setPackage(pkg)
                putExtras(resultIntent)
            }
            sendBroadcast(broadcast)
        } catch (_: Throwable) {}

        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun cancelPurchase() {
        val cancelIntent = Intent().apply {
            putExtra("RESPONSE_CODE", 1)
        }
        setResult(Activity.RESULT_CANCELED, cancelIntent)
        finish()
    }
}

@Composable
private fun SheetRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
