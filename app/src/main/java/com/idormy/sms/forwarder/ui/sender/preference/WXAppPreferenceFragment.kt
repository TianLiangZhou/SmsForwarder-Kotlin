package com.idormy.sms.forwarder.ui.sender.preference

import com.idormy.sms.forwarder.R

class WXAppPreferenceFragment : SenderPreferenceFragment() {
    override fun getChildView(): Int {
        return R.xml.pref_sender_wxapp
    }
}