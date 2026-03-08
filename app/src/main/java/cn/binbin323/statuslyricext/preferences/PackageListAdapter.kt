/*
 * Copyright (C) 2012-2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.binbin323.statuslyricext.preferences

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import cn.binbin323.statuslyricext.R
import java.util.Collections
import java.util.LinkedList
import java.util.TreeSet

class PackageListAdapter(context: Context) : BaseAdapter(), Runnable {

    private val mPm: PackageManager = context.packageManager
    private val mInflater: LayoutInflater = LayoutInflater.from(context)
    private val mInstalledPackages: MutableList<PackageItem> = LinkedList()

    companion object {
        // Packages which don't have launcher icons, but which we want to show nevertheless
        private val PACKAGE_WHITELIST = arrayOf(
            "android",                         /* system server */
            "com.android.systemui",            /* system UI */
            "com.android.providers.downloads"  /* download provider */
        )
    }

    private val mHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val item = msg.obj as PackageItem
            val index = Collections.binarySearch(mInstalledPackages, item)
            if (index < 0) {
                mInstalledPackages.add(-index - 1, item)
            } else {
                mInstalledPackages[index].activityTitles.addAll(item.activityTitles)
            }
            notifyDataSetChanged()
        }
    }

    class PackageItem(
        val packageName: String,
        val title: CharSequence,
        val icon: Drawable
    ) : Comparable<PackageItem> {
        val activityTitles: TreeSet<CharSequence> = TreeSet()

        override fun compareTo(other: PackageItem): Int {
            val result = title.toString().compareTo(other.title.toString(), ignoreCase = true)
            return if (result != 0) result else packageName.compareTo(other.packageName)
        }
    }

    init {
        reloadList()
    }

    override fun getCount(): Int = synchronized(mInstalledPackages) { mInstalledPackages.size }

    override fun getItem(position: Int): PackageItem =
        synchronized(mInstalledPackages) { mInstalledPackages[position] }

    override fun getItemId(position: Int): Long =
        synchronized(mInstalledPackages) { mInstalledPackages[position].packageName.hashCode().toLong() }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: ViewHolder
        val view: View
        if (convertView != null) {
            view = convertView
            holder = view.tag as ViewHolder
        } else {
            view = mInflater.inflate(R.layout.applist_preference_icon, null, false)
            holder = ViewHolder(
                title = view.findViewById(R.id.title),
                summary = view.findViewById(R.id.summary),
                icon = view.findViewById(R.id.icon)
            )
            view.tag = holder
        }

        val appInfo = getItem(position)
        holder.title.text = appInfo.title
        holder.icon.setImageDrawable(appInfo.icon)

        var needSummary = appInfo.activityTitles.size > 0
        if (appInfo.activityTitles.size == 1 &&
            TextUtils.equals(appInfo.title, appInfo.activityTitles.first())
        ) {
            needSummary = false
        }

        if (needSummary) {
            holder.summary.text = TextUtils.join(", ", appInfo.activityTitles)
            holder.summary.visibility = View.VISIBLE
        } else {
            holder.summary.visibility = View.GONE
        }

        return view
    }

    private fun reloadList() {
        mInstalledPackages.clear()
        Thread(this).start()
    }

    override fun run() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val installedApps = mPm.queryIntentActivities(mainIntent, 0)
        for (info in installedApps) {
            val appInfo = info.activityInfo.applicationInfo
            val item = PackageItem(appInfo.packageName, appInfo.loadLabel(mPm), appInfo.loadIcon(mPm))
            item.activityTitles.add(info.loadLabel(mPm))
            mHandler.obtainMessage(0, item).sendToTarget()
        }
        for (packageName in PACKAGE_WHITELIST) {
            try {
                val appInfo = mPm.getApplicationInfo(packageName, 0)
                val item = PackageItem(appInfo.packageName, appInfo.loadLabel(mPm), appInfo.loadIcon(mPm))
                mHandler.obtainMessage(0, item).sendToTarget()
            } catch (e: PackageManager.NameNotFoundException) {
                // package not present – skip
            }
        }
    }

    private data class ViewHolder(
        val title: TextView,
        val summary: TextView,
        val icon: ImageView
    )
}
