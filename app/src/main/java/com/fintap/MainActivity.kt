package com.fintap

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout

// Screen 0 = dashboard, screen 1 + RANGE_x = detailed listing for that range, last = back-tap setup.
private const val SCREEN_DASHBOARD = 0
private val NAV_TITLES = arrayOf("Dashboard") + RANGE_TITLES + arrayOf("Back tap (experimental)")
private val SCREEN_BACK_TAP = NAV_TITLES.size - 1
private const val MENU_ADD = 1

class MainActivity : Activity() {
    private lateinit var drawer: DrawerLayout
    private lateinit var nav: ListView
    private lateinit var content: FrameLayout
    private var screen = SCREEN_DASHBOARD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = savedInstanceState?.getInt("screen") ?: SCREEN_DASHBOARD

        content = FrameLayout(this)

        nav = ListView(this)
        nav.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, NAV_TITLES)
        nav.choiceMode = ListView.CHOICE_MODE_SINGLE
        nav.setBackgroundColor(themeColor(android.R.attr.colorBackground))
        nav.setPadding(0, dp(8), 0, 0)
        nav.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            show(pos)
            drawer.closeDrawer(nav)
        }

        drawer = DrawerLayout(this)
        drawer.addView(content, DrawerLayout.LayoutParams(MATCH, MATCH))
        drawer.addView(nav, DrawerLayout.LayoutParams(dp(280), MATCH, Gravity.START))
        setContentView(drawer)

        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)
    }

    override fun onResume() {
        super.onResume()
        render() // Data may have changed via quick-add while we were paused.
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("screen", screen)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD, 0, "Add")
            .setIcon(R.drawable.ic_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (drawer.isDrawerOpen(nav)) drawer.closeDrawer(nav) else drawer.openDrawer(nav)
                return true
            }
            MENU_ADD -> {
                startActivity(Intent(this, QuickAddActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("platform back handling is fine for a single-activity app")
    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(nav) -> drawer.closeDrawer(nav)
            screen != SCREEN_DASHBOARD -> show(SCREEN_DASHBOARD)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun show(s: Int) {
        screen = s
        render()
    }

    private fun render() {
        nav.setItemChecked(screen, true)
        title = if (screen == SCREEN_DASHBOARD) "finTap" else NAV_TITLES[screen]
        content.removeAllViews()
        content.addView(
            when (screen) {
                SCREEN_DASHBOARD -> dashboardView()
                SCREEN_BACK_TAP -> backTapView()
                else -> listingView(screen - 1)
            }
        )
    }

    // ---- Dashboard: day / week / month totals, then today's entries.

    private fun dashboardView(): View {
        val db = Db.get(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))

        for (kind in intArrayOf(RANGE_TODAY, RANGE_WEEK, RANGE_MONTH)) {
            val r = rangeOf(kind)
            val t = db.total(r[0], r[1])
            col.addView(card(RANGE_TITLES[kind], t[0], t[1]) { show(1 + kind) })
        }

        val today = rangeOf(RANGE_TODAY)
        val items = db.list(today[0], today[1])
        val header = text("Today's entries", 16f, bold = true)
        header.setPadding(dp(4), dp(20), 0, dp(8))
        col.addView(header)
        if (items.isEmpty()) {
            val empty = text("Nothing yet. Double-tap the back of the phone, or press +.", 14f)
            empty.setPadding(dp(4), 0, 0, 0)
            empty.alpha = 0.7f
            col.addView(empty)
        }
        for (e in items) {
            val row = rowView(null)
            bindRow(row, e)
            row.setOnClickListener { edit(e) }
            col.addView(row)
        }

        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }

    private fun card(label: String, paise: Long, count: Long, onTap: () -> Unit): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundResource(R.drawable.card_bg)
        c.setPadding(dp(20), dp(16), dp(20), dp(16))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(12)
        c.layoutParams = lp

        val l = text(label, 14f)
        l.alpha = 0.75f
        c.addView(l)
        c.addView(text(formatPaise(paise), 30f, bold = true))
        val n = text(if (count == 1L) "1 expense" else "$count expenses", 13f)
        n.alpha = 0.6f
        c.addView(n)
        c.setOnClickListener { onTap() }
        return c
    }

    // ---- Back-tap setup. The on/off switch is the system accessibility toggle, off by default;
    // this screen only explains it and links there.

    private fun backTapEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, TapService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun backTapView(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(20))

        val on = backTapEnabled()
        col.addView(text(if (on) "Back tap is ON" else "Back tap is OFF", 22f, bold = true))

        val body = text(
            "Double-tap the back of the phone to open Add expense.\n\n" +
                "Experimental: tuned on one phone (Nothing A059) and one person's taps. " +
                "On your phone it may miss taps, or fire when it shouldn't. If it misfires, turn it off.\n\n" +
                "Works only while the phone is unlocked and the screen is on. " +
                "Uses the motion sensor only; reads nothing on screen, has no internet access.\n\n" +
                "To turn on: Accessibility → finTap back tap → On.\n\n" +
                "If the switch is greyed out (\"Restricted setting\"): open Settings → Apps → finTap → ⋮ menu (top right) → " +
                "Allow restricted settings, then try again. Android does this for apps installed outside Play Store.",
            15f
        )
        body.setPadding(0, dp(12), 0, dp(20))
        body.setLineSpacing(0f, 1.15f)
        col.addView(body)

        val open = android.widget.Button(this)
        open.text = if (on) "Turn off in Accessibility settings" else "Open Accessibility settings"
        open.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(open)

        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }

    // ---- Detailed listing for one range.

    private fun listingView(kind: Int): View {
        val db = Db.get(this)
        val r = rangeOf(kind)
        val items = db.list(r[0], r[1])
        var sum = 0L
        for (e in items) sum += e.paise

        val list = ListView(this)
        val header = text("${formatPaise(sum)}  ·  ${items.size} expenses", 18f, bold = true)
        header.setPadding(dp(20), dp(16), dp(20), dp(12))
        list.addHeaderView(header, null, false)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(pos: Int) = items[pos]
            override fun getItemId(pos: Int) = items[pos].id
            override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
                val row = rowView(convert)
                bindRow(row, items[pos])
                return row
            }
        }
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            // Header occupies position 0.
            val i = pos - list.headerViewsCount
            if (i >= 0 && i < items.size) edit(items[i])
        }
        return list
    }

    // ---- Row: name + when on the left, amount on the right.

    private class RowHolder(val name: TextView, val whenText: TextView, val amount: TextView)

    private fun rowView(convert: View?): View {
        if (convert != null && convert.tag is RowHolder) return convert
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), dp(12), dp(20), dp(12))
        row.isClickable = false

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = text("", 16f)
        val whenText = text("", 12f)
        whenText.alpha = 0.6f
        left.addView(name)
        left.addView(whenText)
        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))

        val amount = text("", 16f, bold = true)
        row.addView(amount)
        row.tag = RowHolder(name, whenText, amount)
        return row
    }

    private fun bindRow(row: View, e: Expense) {
        val h = row.tag as RowHolder
        h.name.text = e.name
        h.whenText.text = formatWhen(e.ts)
        h.amount.text = formatPaise(e.paise)
    }

    // ---- Edit / delete.

    private fun edit(e: Expense) {
        val form = ExpenseForm(this, e.name, e.paise)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit expense")
            .setView(form.root)
            .setPositiveButton("Save", null) // Overridden below so a bad amount keeps the dialog open.
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ -> confirmDelete(e) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = form.read()
                if (r != null) {
                    Db.get(this).update(e.id, r.first, r.second)
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(e: Expense) {
        AlertDialog.Builder(this)
            .setMessage("Delete ${e.name} ${formatPaise(e.paise)}?")
            .setPositiveButton("Delete") { _, _ ->
                Db.get(this).delete(e.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0)
        a.recycle()
        return c
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
