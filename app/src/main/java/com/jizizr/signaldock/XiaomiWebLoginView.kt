package com.jizizr.signaldock

import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible

/** Native presentation only: keeps the browser attached and measured in every state. */
internal class XiaomiWebLoginView(root: View, onRetry: () -> Unit, onClose: () -> Unit) {
    enum class Stage { PREPARING, LOADING, READY, VERIFYING, SUCCESS, FAILED }

    private val browser = root.findViewById<View>(R.id.login_browser)
    private val hint = root.findViewById<View>(R.id.login_hint)
    private val hintText = root.findViewById<TextView>(R.id.login_hint_text)
    private val pageProgress = root.findViewById<View>(R.id.login_page_progress)
    private val statePanel = root.findViewById<View>(R.id.login_state)
    private val title = root.findViewById<TextView>(R.id.login_state_title)
    private val body = root.findViewById<TextView>(R.id.login_state_body)
    private val note = root.findViewById<View>(R.id.login_state_note)
    private val progress = root.findViewById<View>(R.id.login_state_progress)
    private val icon = root.findViewById<ImageView>(R.id.login_state_icon)
    private val actions = root.findViewById<View>(R.id.login_actions)
    private val primaryAction = root.findViewById<Button>(R.id.login_primary_action)
    private val secondaryAction = root.findViewById<View>(R.id.login_secondary_action)

    var stage: Stage = Stage.PREPARING
        private set
    private var keyboardVisible = false

    init {
        root.findViewById<View>(R.id.login_back).setOnClickListener { onClose() }
        secondaryAction.setOnClickListener { onClose() }
        primaryAction.setOnClickListener {
            if (stage == Stage.FAILED) onRetry() else onClose()
        }
        show(Stage.PREPARING)
    }

    fun setKeyboardVisible(visible: Boolean) {
        keyboardVisible = visible
        hint.isVisible = !visible && (stage == Stage.LOADING || stage == Stage.READY)
    }

    fun show(stage: Stage, message: String? = null) {
        this.stage = stage
        val showBrowser = stage == Stage.LOADING || stage == Stage.READY
        // INVISIBLE keeps the native WebView measured for the attach/layout load gate.
        browser.visibility = if (showBrowser) View.VISIBLE else View.INVISIBLE
        hint.isVisible = showBrowser && !keyboardVisible
        statePanel.isVisible = !showBrowser
        pageProgress.isVisible = stage == Stage.LOADING
        progress.isVisible = stage == Stage.PREPARING || stage == Stage.VERIFYING
        icon.isVisible = stage == Stage.SUCCESS || stage == Stage.FAILED
        note.isVisible = stage == Stage.SUCCESS
        actions.isVisible = stage == Stage.VERIFYING || stage == Stage.SUCCESS || stage == Stage.FAILED
        secondaryAction.isVisible = stage == Stage.FAILED
        when (stage) {
            Stage.PREPARING -> {
                title.setText(R.string.login_preparing_title)
                body.setText(R.string.login_preparing_body)
            }
            Stage.LOADING -> hintText.setText(R.string.login_loading)
            Stage.READY -> hintText.text = message ?: hintText.context.getString(R.string.login_instructions)
            Stage.VERIFYING -> {
                title.setText(R.string.login_verifying_title)
                body.setText(R.string.login_verifying_body)
                primaryAction.setText(R.string.login_back)
            }
            Stage.SUCCESS -> {
                title.setText(R.string.login_success_title)
                body.setText(R.string.login_success_body)
                icon.setImageResource(R.drawable.ic_login_success)
                primaryAction.setText(R.string.login_done)
            }
            Stage.FAILED -> {
                title.setText(R.string.login_failed_title)
                body.text = message
                icon.setImageResource(R.drawable.ic_login_error)
                primaryAction.setText(R.string.login_retry)
            }
        }
    }
}
