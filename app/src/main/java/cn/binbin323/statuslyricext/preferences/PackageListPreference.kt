/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.binbin323.statuslyricext.preferences

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.ListView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import cn.binbin323.statuslyricext.R
import cn.binbin323.statuslyricext.misc.Constants
import cn.binbin323.statuslyricext.preferences.PackageListAdapter.PackageItem

class PackageListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs), Preference.OnPreferenceClickListener {

    private val mPackageManager: PackageManager = context.packageManager
    private val mPackageAdapter: PackageListAdapter = PackageListAdapter(context)
    private val mAddPackagePref: Preference = makeAddPref()
    private val mPackages: MutableList<String> = mutableListOf()

    init {
        isOrderingAsAdded = false
    }

    private fun makeAddPref(): Preference = Preference(context).apply {
        setTitle(R.string.add_package_to_title)
        setIcon(R.drawable.ic_add)
        isPersistent = false
        onPreferenceClickListener = this@PackageListPreference
    }

    private fun getAppInfo(packageName: String) = try {
        mPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun parsePackageList() {
        mPackages.clear()
        val data = getPersistedString("")
        if (!TextUtils.isEmpty(data)) {
            mPackages.addAll(data.split(";").filter { it.isNotEmpty() })
        }
    }

    private fun refreshCustomApplicationPrefs() {
        parsePackageList()
        removeAll()
        addPreference(mAddPackagePref)
        mPackages.forEach { addPackageToPref(it) }
    }

    private fun savePackagesList() {
        persistString(mPackages.joinToString(";"))
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(Constants.BROADCAST_IGNORED_APP_CHANGED))
    }

    private fun addPackageToPref(packageName: String) {
        val appInfo = getAppInfo(packageName) ?: return
        val pref = Preference(context).apply {
            key = packageName
            title = appInfo.loadLabel(mPackageManager)
            icon = appInfo.loadIcon(mPackageManager)
            isPersistent = false
            onPreferenceClickListener = this@PackageListPreference
        }
        addPreference(pref)
    }

    private fun addPackageToList(packageName: String) {
        if (!mPackages.contains(packageName)) {
            mPackages.add(packageName)
            addPackageToPref(packageName)
            savePackagesList()
        }
    }

    private fun removePackageFromList(packageName: String) {
        mPackages.remove(packageName)
        savePackagesList()
    }

    override fun onAttached() {
        super.onAttached()
        refreshCustomApplicationPrefs()
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val builder = AlertDialog.Builder(context)
        if (preference == mAddPackagePref) {
            val appsList = ListView(context).apply { adapter = mPackageAdapter }
            builder.setTitle(R.string.profile_choose_app)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(appsList)
            val dialog = builder.create()
            appsList.setOnItemClickListener { parent, _, position, _ ->
                val info = parent.getItemAtPosition(position) as PackageItem
                addPackageToList(info.packageName)
                dialog.cancel()
            }
            dialog.show()
        } else {
            val key = preference.key ?: return false
            builder.setTitle(R.string.dialog_delete_title)
                .setMessage(R.string.dialog_delete_message)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    removePackageFromList(key)
                    removePreference(preference)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return true
    }
}
