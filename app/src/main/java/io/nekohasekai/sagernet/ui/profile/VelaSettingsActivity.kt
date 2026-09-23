package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.vela.VelaBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

class VelaSettingsActivity : ProfileSettingsActivity<VelaBean>() {
    override fun createEntity() = VelaBean().applyDefaultValues()

    override fun VelaBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUserId = clientPrivateKey
        DataStore.serverPassword = serverPublicKey
    }

    override fun VelaBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        clientPrivateKey = DataStore.serverUserId
        serverPublicKey = DataStore.serverPassword
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.vela_preferences)
        findPreference<EditTextPreference>(Key.SERVER_USER_ID)!!.apply {
            summaryProvider = null
            dialogLayoutResource = R.layout.layout_password_dialog
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }
}
