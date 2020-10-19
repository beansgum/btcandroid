/*
 * Copyright (c) 2018-2019 The Decred developers
 * Use of this source code is governed by an ISC
 * license that can be found in the LICENSE file.
 */

package com.btcandroid.dialog.send

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.btcandroid.R
import com.btcandroid.data.Account
import com.btcandroid.data.Constants
import com.btcandroid.extensions.hide
import com.btcandroid.extensions.show
import com.btcandroid.extensions.toggleVisibility
import com.btcandroid.util.CoinFormat
import com.btcandroid.util.GetExchangeRate
import com.btcandroid.util.WalletData
import btclibwallet.Btclibwallet
import kotlinx.android.synthetic.main.fee_layout.view.*
import kotlinx.android.synthetic.main.send_page_amount_card.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat


const val AmountRelativeSize = 0.625f
val usdAmountFormat: DecimalFormat = DecimalFormat("0.0000")
val usdAmountFormat2: DecimalFormat = DecimalFormat("0.00")
val dcrFormat = DecimalFormat("#.########")

class AmountInputHelper(private val layout: LinearLayout, private val scrollToBottom: () -> Unit) : TextWatcher, View.OnClickListener, GetExchangeRate.ExchangeRateCallback {

    val context = layout.context

    var exchangeEnabled = true
    var exchangeDecimal: BigDecimal? = null
    var currencyIsDCR = true

    var selectedAccount: Account? = null
        set(value) {
            layout.spendable_balance.text = context.getString(R.string.spendable_bal_format,
                    CoinFormat.formatBitcoin(value!!.balance.spendable))
            field = value
        }

    var usdAmount: BigDecimal? = null
    var btcAmount: BigDecimal? = null
    val enteredAmount: BigDecimal?
        get() {
            val s = layout.send_amount.text.toString()
            try {
                return BigDecimal(s)
            } catch (e: Exception) {
            }
            return null
        }

    val maxDecimalPlaces: Int
        get() {
            return if (currencyIsDCR)
                8
            else
                2
        }

    init {
        layout.send_amount.addTextChangedListener(this)
        layout.send_amount.setOnFocusChangeListener { _, _ ->
            setBackground()
        }

        layout.iv_send_clear.setOnClickListener(this)
        layout.iv_expand_fees.setOnClickListener(this)

        layout.swap_currency.setOnClickListener(this)
        layout.send_equivalent_value.setOnClickListener(this)

        layout.exchange_error_retry.setOnClickListener(this)

        layout.send_amount_layout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                // focus amount input on touch
                layout.send_amount.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(layout.send_amount, InputMethodManager.SHOW_IMPLICIT)
                return@setOnTouchListener true
            }
            return@setOnTouchListener false
        }

        fetchExchangeRate()
    }

    private fun fetchExchangeRate() {
        val multiWallet = WalletData.multiWallet!!
        val currencyConversion = multiWallet.readInt32ConfigValueForKey(Btclibwallet.CurrencyConversionConfigKey, Constants.DEF_CURRENCY_CONVERSION)

        exchangeEnabled = currencyConversion > 0

        if (!exchangeEnabled) {
            return
        }

        println("Getting exchange rate")
        val userAgent = multiWallet.readStringConfigValueForKey(Btclibwallet.UserAgentConfigKey)
        GetExchangeRate(userAgent, this).execute()
    }

    private fun setBackground() = GlobalScope.launch(Dispatchers.Main) {
        var backgroundResource: Int

        backgroundResource = if (layout.send_amount.hasFocus()) {
            R.drawable.input_background_active
        } else {
            R.drawable.input_background
        }

        if (layout.amount_error_text.text.isNotEmpty()) {
            backgroundResource = R.drawable.input_background_error
        }

        layout.amount_input_container.setBackgroundResource(backgroundResource)
    }

    override fun onClick(v: View?) {
        when (v!!.id) {
            R.id.iv_expand_fees -> {

                layout.fee_verbose.toggleVisibility()
                scrollToBottom()
                val img = if (layout.fee_verbose.visibility == View.VISIBLE) {
                    R.drawable.ic_collapse
                } else {
                    R.drawable.ic_expand
                }

                layout.iv_expand_fees.setImageResource(img)
            }

            R.id.swap_currency, R.id.send_equivalent_value -> {
                if (exchangeDecimal == null) {
                    return
                }

                if (currencyIsDCR) {
                    layout.currency_label.setText(R.string.usd)
                } else {
                    layout.currency_label.setText(R.string.btc)
                }

                currencyIsDCR = !currencyIsDCR

                layout.send_amount.removeTextChangedListener(this)
                if (enteredAmount != null) {
                    if (currencyIsDCR) {
                        val dcr = dcrFormat.format(btcAmount!!.setScale(8, BigDecimal.ROUND_HALF_EVEN).toDouble())
                        layout.send_amount.setText(CoinFormat.format(dcr, AmountRelativeSize))
                    } else {
                        val usd = usdAmount!!.setScale(2, BigDecimal.ROUND_HALF_EVEN).toPlainString()
                        layout.send_amount.setText(usd)
                    }

                    layout.send_amount.setSelection(layout.send_amount.text.length) //move cursor to end
                } else {
                    layout.send_amount.text = null
                }
                layout.send_amount.addTextChangedListener(this)

                displayEquivalentValue()
                amountChanged?.invoke(false)
            }
            R.id.exchange_error_retry -> {
                layout.exchange_layout.hide()
                fetchExchangeRate()
            }
            R.id.iv_send_clear -> {
                layout.send_amount.text = null
            }
        }
    }

    fun setAmountBTC(coin: Double) {
        if (coin > 0) {
            layout.send_amount.removeTextChangedListener(this)

            btcAmount = BigDecimal(coin)
            usdAmount = btcToUSD(exchangeDecimal, btcAmount!!.toDouble())

            if (currencyIsDCR) {
                val dcr = Btclibwallet.amountAtom(coin)
                val amountString = CoinFormat.formatBitcoin(dcr, CoinFormat.btcWithoutCommas)
                layout.send_amount.setText(CoinFormat.format(amountString, AmountRelativeSize))
            } else {
                val usd = usdAmount!!.setScale(2, BigDecimal.ROUND_HALF_EVEN).toPlainString()
                layout.send_amount.setText(usd)
            }

            layout.send_amount.setSelection(layout.send_amount.text.length) //move cursor to end

            layout.send_amount.addTextChangedListener(this)
            layout.currency_label.setTextColor(context.resources.getColor(R.color.darkBlueTextColor))
        } else {
            layout.send_amount.text = null
        }

        displayEquivalentValue()
        hideOrShowClearButton()
    }

    fun setAmountBTC(dcr: Long) = setAmountBTC(Btclibwallet.amountCoin(dcr))

    fun setError(error: String?) = GlobalScope.launch(Dispatchers.Main) {
        if (error == null) {
            layout.amount_error_text.text = null
            layout.amount_error_text.hide()
            setBackground()
            return@launch
        }

        layout.amount_error_text.apply {
            text = error
            show()
        }
        setBackground()
    }

    var amountChanged: ((byUser: Boolean) -> Unit?)? = null
    override fun afterTextChanged(s: Editable?) {
        if (s.isNullOrEmpty()) {
            layout.currency_label.setTextColor(context.resources.getColor(R.color.lightGrayTextColor))
            hideOrShowClearButton()
        } else {
            layout.currency_label.setTextColor(context.resources.getColor(R.color.darkBlueTextColor))
            hideOrShowClearButton()
            if (currencyIsDCR) {
                CoinFormat.formatSpannable(s, AmountRelativeSize)
            }
        }

        setError(null)

        if (enteredAmount != null) {
            if (currencyIsDCR) {
                btcAmount = enteredAmount!!
                usdAmount = btcToUSD(exchangeDecimal, btcAmount!!.toDouble())
            } else {
                usdAmount = enteredAmount!!
                btcAmount = usdToDCR(exchangeDecimal, usdAmount!!.toDouble())
            }
        } else {
            btcAmount = null
            usdAmount = null
        }

        displayEquivalentValue()

        amountChanged?.invoke(true)
    }

    private fun displayEquivalentValue() {

        if (currencyIsDCR) {
            val usd = if (usdAmount == null) {
                0
            } else {
                usdAmount!!.toDouble()
            }

            val usdStr = usdAmountFormat2.format(usd)
            layout.send_equivalent_value.text = context.getString(R.string.x_usd, usdStr)
        } else {
            val dcr = if (btcAmount == null) {
                0.0
            } else {
                btcAmount!!.toDouble()
            }

            val dcrStr = dcrFormat.format(dcr)
            layout.send_equivalent_value.text = context.getString(R.string.x_btc, dcrStr)
        }
    }

    private fun hideOrShowClearButton() {
        if (layout.send_amount.text.isEmpty()) {
            layout.iv_send_clear.hide()
        } else {
            layout.iv_send_clear.show()
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun onExchangeRateSuccess(rate: GetExchangeRate.BittrexRateParser) {
        exchangeDecimal = rate.usdRate

        GlobalScope.launch(Dispatchers.Main) {
            layout.exchange_error_layout.hide()
            layout.send_equivalent_value.show()
            layout.exchange_layout.show()
        }

        if (btcAmount != null) {
            usdAmount = btcToUSD(exchangeDecimal, btcAmount!!.toDouble())
        }

        displayEquivalentValue()
        amountChanged?.invoke(false)
    }

    override fun onExchangeRateError(e: Exception) {
        e.printStackTrace()

        GlobalScope.launch(Dispatchers.Main) {
            layout.exchange_error_layout.show()

            layout.send_equivalent_value.hide()
            layout.exchange_layout.show()
        }
    }
}

fun dcrToFormattedUSD(exchangeDecimal: BigDecimal?, dcr: Double, scale: Int = 4): String {
    if (scale == 4) {
        return usdAmountFormat.format(
                btcToUSD(exchangeDecimal, dcr)!!.setScale(scale, BigDecimal.ROUND_HALF_EVEN).toDouble())
    }

    return usdAmountFormat2.format(
            btcToUSD(exchangeDecimal, dcr)!!.setScale(scale, BigDecimal.ROUND_HALF_EVEN).toDouble())
}

fun btcToUSD(exchangeDecimal: BigDecimal?, dcr: Double): BigDecimal? {
    val dcrDecimal = BigDecimal(dcr)
    return exchangeDecimal?.multiply(dcrDecimal)
}

fun usdToDCR(exchangeDecimal: BigDecimal?, usd: Double): BigDecimal? {
    if (exchangeDecimal == null) {
        return null
    }

    val usdDecimal = BigDecimal(usd)
    // using 8 to be safe
    return usdDecimal.divide(exchangeDecimal, 8, RoundingMode.HALF_EVEN)
}