package com.aistock.assistant.features.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistock.assistant.R
import com.aistock.assistant.data.Access
import com.aistock.assistant.server.PayInfo
import com.aistock.assistant.server.PaymentClaim
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.utils.Money
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A UPI "pay" link any UPI app (GPay, PhonePe, Paytm, BHIM) opens with the amount filled in. */
fun upiLink(upiId: String, payee: String, rupees: Long, note: String): Uri = Uri.Builder().scheme("upi").authority("pay")
    .appendQueryParameter("pa", upiId).appendQueryParameter("pn", payee)
    .appendQueryParameter("am", "$rupees.00").appendQueryParameter("cu", "INR")
    .appendQueryParameter("tn", note.take(50)).build()

/** A black-and-white QR code for [text], or null if it cannot be made. */
fun qrBitmap(text: String, size: Int): androidx.compose.ui.graphics.ImageBitmap? = try {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val px = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.RGB_565).asImageBitmap()
} catch (e: Exception) { null }

/** UPI transaction IDs (UTR / reference number) are 6 to 30 letters or digits; spaces are dropped. */
fun cleanUtr(s: String): String = s.filter { it.isLetterOrDigit() }.take(30)

/**
 * Plan & payment: how long the trial or plan lasts, and paying after it by UPI. The owner pays from any UPI app, then
 * types the transaction ID from that app; the plan starts when the Follower team has checked the payment.
 */
@Composable
fun PlanScreen(nav: AppNav, vm: AccountViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle(initialValue = null)
    val p = prefs ?: return
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<PayInfo?>(null) }
    var claims by remember { mutableStateOf<List<PaymentClaim>>(emptyList()) }
    var chosen by remember { mutableStateOf("basic") }
    var utr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // Back from the Razorpay page in the browser: ask the server whether the payment went through.
    var waitingForCard by remember { mutableStateOf(false) }

    suspend fun load() {
        error = ""
        try { vm.api.checkPayments() } catch (e: Exception) { }
        try { info = vm.api.payInfo() } catch (e: Exception) { error = ctx.getString(R.string.signin_no_internet) }
        try { vm.api.refreshPlan(); claims = vm.api.payments() } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) { load() }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && waitingForCard) scope.launch {
                try { if (vm.api.checkPayments() > 0) { waitingForCard = false; toast(ctx.getString(R.string.payment_card_done)) } } catch (e: Exception) { }
                load()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val days = Access.daysLeft(p)
    val readOnly = Access.readOnly(p)
    val end = if (p.planEnd > 0) SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(p.planEnd)) else ""

    FormPage(stringResource(R.string.plan_and_payment), nav.back) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(
                when {
                    readOnly && p.planStatus == "trial" -> stringResource(R.string.plan_state_ended)
                    readOnly -> stringResource(R.string.plan_state_plan_ended)
                    p.planStatus == "trial" -> stringResource(R.string.plan_state_trial, days)
                    else -> stringResource(R.string.plan_state_paid, p.planName, end)
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (readOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (readOnly) Text(stringResource(R.string.read_only_body), style = MaterialTheme.typography.bodyMedium)
            else if (end.isNotBlank()) LabelValue(stringResource(R.string.plan_until), end)
            if (p.planPending > 0) Text(stringResource(R.string.payment_waiting), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

        val i = info
        if (i != null) {
            SectionTitle(stringResource(R.string.choose_plan))
            for (plan in i.plans) {
                AppCard(Modifier.fillMaxWidth(), onClick = { chosen = plan.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen == plan.id, { chosen = plan.id })
                        Text("${plan.name} · ${Money.format(plan.priceMinor.toLong(), "₹")} / ${plan.periodDays} ${stringResource(R.string.days)}", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(R.string.plan_includes, plan.aiRequests, plan.voiceMinutes, plan.maxStaff), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val plan = i.plans.firstOrNull { it.id == chosen } ?: i.plans.firstOrNull()
            if (plan != null && i.cards) {
                // Razorpay: cards, net banking, UPI and wallets on one page; the plan starts by itself when paid.
                SectionTitle(stringResource(R.string.pay_card_title))
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val url = vm.api.paymentLink(plan.id)
                            waitingForCard = true
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) { toast(e.message ?: e.toString()) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.pay_card, Money.format(plan.priceMinor.toLong(), "₹"))) }
                Text(stringResource(R.string.pay_card_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (plan != null && i.upiId.isNotBlank()) {
                val uri = upiLink(i.upiId, i.payeeName, plan.priceMinor / 100L, "Follower ${plan.name} ${p.serverEmail}")
                SectionTitle(stringResource(if (i.cards) R.string.pay_upi_title_or else R.string.pay_step1))
                // The UPI ID itself is not shown: the QR code and the UPI app button carry it.
                val qr = remember(uri.toString()) { qrBitmap(uri.toString(), 640) }
                if (qr != null) androidx.compose.foundation.Image(qr, contentDescription = stringResource(R.string.upi_qr), modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.upi_qr_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), ctx.getString(R.string.pay_with_upi))) }
                    catch (e: Exception) { toast(ctx.getString(R.string.no_upi_app)) }
                }) { Text(stringResource(R.string.pay_amount_upi, Money.format(plan.priceMinor.toLong(), "₹"))) }
                SectionTitle(stringResource(R.string.pay_step2))
                OutlinedTextField(utr, { utr = cleanUtr(it) }, label = { Text(stringResource(R.string.utr_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Text(stringResource(R.string.utr_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && utr.length >= 6, onClick = {
                    busy = true
                    scope.launch {
                        try { vm.api.claimPayment(plan.id, utr); utr = ""; toast(ctx.getString(R.string.payment_sent)); load() }
                        catch (e: Exception) { toast(e.message ?: e.toString()) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.send_for_approval)) }
            }
            if (i.upiId.isBlank() && !i.cards) {
                Text(stringResource(R.string.plan_how_to_pay), style = MaterialTheme.typography.bodyMedium)
            }
            if (i.supportPhone.isNotBlank()) {
                OutlinedButton(onClick = { try { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${i.supportPhone}"))) } catch (e: Exception) { } }) {
                    Text(stringResource(R.string.call_support, i.supportPhone))
                }
            }
        }

        if (claims.isNotEmpty()) {
            SectionTitle(stringResource(R.string.your_payments))
            for (c in claims) {
                AppCard(Modifier.fillMaxWidth()) {
                    LabelValue("${c.planId} · ${Money.format(c.amountMinor.toLong(), "₹")}", stringResource(when (c.status) {
                        "approved" -> R.string.payment_approved
                        "rejected" -> R.string.payment_rejected
                        else -> R.string.payment_pending
                    }))
                    Text("UTR ${c.utr} · ${c.createdAt.take(10)}" + if (c.note.isNotBlank()) " · ${c.note}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton(onClick = { scope.launch { load() } }) { Text(stringResource(R.string.refresh)) }
        Spacer(Modifier.height(32.dp))
    }
}
