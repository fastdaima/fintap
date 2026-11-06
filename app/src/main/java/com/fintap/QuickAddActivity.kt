package com.fintap

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

// Target of the back double-tap (Pixel Quick Tap -> Open app -> "finTap Add").
// Floating window, name field focused, keyboard up. Save -> finish. Nothing else loads.
class QuickAddActivity : Activity() {
    companion object {
        const val EXTRA_FIRE_ID = "fire_id"
    }

    private lateinit var form: ExpenseForm
    // Non-zero when opened by a back double-tap. Its outcome is the misfire label:
    // "saved" = real use, "dismissed" = possible misfire (or a test).
    private var fireId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fireId = intent.getLongExtra(EXTRA_FIRE_ID, 0L)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(4), dp(20), dp(4), dp(12))

        val title = text("Add expense", 20f, bold = true)
        title.setPadding(dp(24), 0, dp(24), dp(4))
        root.addView(title)

        form = ExpenseForm(this, "", null)
        root.addView(form.root)

        val buttons = LinearLayout(this)
        buttons.gravity = Gravity.END
        buttons.setPadding(dp(16), 0, dp(16), 0)
        val cancel = Button(this, null, android.R.attr.borderlessButtonStyle)
        cancel.text = "Cancel"
        cancel.setOnClickListener { finish() }
        val save = Button(this, null, android.R.attr.borderlessButtonStyle)
        save.text = "Save"
        save.setOnClickListener { save() }
        buttons.addView(cancel)
        buttons.addView(save)
        root.addView(buttons)

        form.priceField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }

        setContentView(root)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        form.nameField.requestFocus()
    }

    private fun save() {
        val r = form.read() ?: return
        Db.get(this).insert(r.first, r.second, System.currentTimeMillis())
        reportOutcome("saved")
        Toast.makeText(this, "Saved ${r.first} ${formatPaise(r.second)}", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Cancel, back, tap outside, or switching away all land here without a save.
        reportOutcome("dismissed")
        finish()
    }

    private fun reportOutcome(what: String) {
        if (fireId == 0L) return
        DecisionLog.append(this, "${DecisionLog.stamp()} OUTCOME id=$fireId $what\n")
        fireId = 0L
    }
}
