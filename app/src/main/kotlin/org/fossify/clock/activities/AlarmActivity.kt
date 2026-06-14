package org.fossify.clock.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.AnimationUtils
import org.fossify.clock.R
import org.fossify.clock.databinding.ActivityAlarmBinding
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.helpers.*
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.MINUTE_SECONDS
import org.fossify.commons.helpers.isOreoMr1Plus
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Random
import kotlin.math.max
import kotlin.math.min

class AlarmActivity : SimpleActivity() {
    companion object {
        private const val REMINDER_DRAGGABLE_BACKGROUND_ALPHA = 0.2f
        private const val REMINDER_GUIDE_SHOW_DURATION = 2000L
        private const val DRAG_ACTION_THRESHOLD_PX = 50f
    }

    private val swipeGuideFadeHandler = Handler(Looper.getMainLooper())
    private var alarm: Alarm? = null
    private var didVibrate = false
    private var dragDownX = 0f

    private val binding by viewBinding(ActivityAlarmBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        showOverLockscreen()
        updateTextColors(binding.root)

        val id = intent.getIntExtra(ALARM_ID, -1)
        alarm = dbHelper.getAlarmWithId(id)
        if (alarm == null) {
            finish()
            return
        }

        val label = alarm!!.label.ifEmpty {
            getString(org.fossify.commons.R.string.alarm)
        }

        binding.reminderTitle.text = label
        binding.reminderText.text = getFormattedTime(
            passedSeconds = getPassedSeconds(),
            showSeconds = false,
            makeAmPmSmaller = false
        )

        setupAlarmButtons()
        EventBus.getDefault().register(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAlarmButtons() {
        binding.reminderDraggableBackground.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.pulsing_animation)
        )
        binding.reminderDraggableBackground.applyColorFilter(getProperPrimaryColor())

        val textColor = getProperTextColor()
        binding.reminderDismiss.applyColorFilter(textColor)
        binding.reminderDraggable.applyColorFilter(textColor)
        binding.reminderSnooze.applyColorFilter(textColor)

        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f

        binding.reminderDismiss.onGlobalLayout {
            minDragX = binding.reminderSnooze.left.toFloat()
            maxDragX = binding.reminderDismiss.left.toFloat()
            initialDraggableX = binding.reminderDraggable.left.toFloat()
        }

        binding.reminderDraggable.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                    binding.reminderDraggableBackground.animate().alpha(0f)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    if (!didVibrate) {
                        binding.reminderDraggable.animate().x(initialDraggableX).withEndAction {
                            binding.reminderDraggableBackground
                                .animate()
                                .alpha(REMINDER_DRAGGABLE_BACKGROUND_ALPHA)
                        }

                        binding.reminderGuide.animate().alpha(1f).start()
                        swipeGuideFadeHandler.removeCallbacksAndMessages(null)
                        swipeGuideFadeHandler.postDelayed({
                            binding.reminderGuide.animate().alpha(0f).start()
                        }, REMINDER_GUIDE_SHOW_DURATION)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    binding.reminderDraggable.x = min(
                        a = maxDragX,
                        b = max(minDragX, event.rawX - dragDownX)
                    )

                    if (binding.reminderDraggable.x >= maxDragX - DRAG_ACTION_THRESHOLD_PX) {
                        if (!didVibrate) {
                            binding.reminderDraggable.performHapticFeedback()
                            didVibrate = true
                            dismissAlarmAndFinish()
                        }
                    } else if (binding.reminderDraggable.x <= minDragX + DRAG_ACTION_THRESHOLD_PX) {
                        if (!didVibrate) {
                            binding.reminderDraggable.performHapticFeedback()
                            didVibrate = true
                            snoozeAlarm()
                        }
                    }
                }
            }
            true
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupAlarmButtons()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            AlarmClock.ACTION_DISMISS_ALARM -> dismissAlarmAndFinish()
            AlarmClock.ACTION_SNOOZE_ALARM -> {
                val durationMinutes = intent.getIntExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, -1)
                if (durationMinutes == -1) {
                    snoozeAlarm()
                } else {
                    snoozeAlarm(durationMinutes)
                }
            }

            else -> {
                // no-op. user probably clicked the notification
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        swipeGuideFadeHandler.removeCallbacksAndMessages(null)
        EventBus.getDefault().unregister(this)
    }

    private fun snoozeAlarm(overrideSnoozeDuration: Int? = null) {
        if (overrideSnoozeDuration != null) {
            dismissAlarmAndFinish(overrideSnoozeDuration)
        } else if (config.useSameSnooze) {
            dismissAlarmAndFinish(config.snoozeTime)
        } else {
            alarmController.silenceAlarm(alarm!!.id)
            showPickSecondsDialog(
                curSeconds = config.snoozeTime * MINUTE_SECONDS,
                isSnoozePicker = true,
                cancelCallback = {
                    dismissAlarmAndFinish()
                },
                callback = {
                    config.snoozeTime = it / MINUTE_SECONDS
                    dismissAlarmAndFinish(config.snoozeTime)
                }
            )
        }
    }

    private fun dismissAlarmAndFinish(snoozeMinutes: Int = -1) {
        if (snoozeMinutes == -1 && alarm?.challengeType != CHALLENGE_NONE) {
            showChallengeDialog()
            return
        }

        realDismissAlarm(snoozeMinutes)
    }

    private fun realDismissAlarm(snoozeMinutes: Int) {
        if (alarm != null) {
            if (snoozeMinutes != -1) {
                alarmController.snoozeAlarm(alarm!!.id, snoozeMinutes)
            } else {
                alarmController.stopAlarm(alarm!!.id)
            }
        }

        finishActivity()
    }

    private fun showChallengeDialog() {
        val currentAlarm = alarm ?: return
        when (currentAlarm.challengeType) {
            CHALLENGE_MATH -> showMathChallenge()
            CHALLENGE_PASSWORD -> showPasswordChallenge()
        }
    }

    private fun showMathChallenge() {
        val random = Random()
        val num1 = random.nextInt(20) + 1
        val num2 = random.nextInt(20) + 1
        val result = num1 + num2
        val mathProblem = "$num1 + $num2 = ?"

        val editText = com.google.android.material.textfield.TextInputEditText(this)
        editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ ->
                didVibrate = false
            }
            .apply {
                setTitle(mathProblem)
                setupDialogStuff(editText, this) { alertDialog ->
                    alertDialog.showKeyboard(editText)
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (editText.value == result.toString()) {
                            realDismissAlarm(-1)
                            alertDialog.dismiss()
                        } else {
                            toast("Incorrect answer")
                        }
                    }
                }
            }
    }

    private fun showPasswordChallenge() {
        val editText = com.google.android.material.textfield.TextInputEditText(this)
        editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ ->
                didVibrate = false
            }
            .apply {
                setTitle("Enter password")
                setupDialogStuff(editText, this) { alertDialog ->
                    alertDialog.showKeyboard(editText)
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (editText.value == alarm?.challengePassword) {
                            realDismissAlarm(-1)
                            alertDialog.dismiss()
                        } else {
                            toast("Incorrect password")
                        }
                    }
                }
            }
    }


    private fun showOverLockscreen() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onAlarmStoppedEvent(event: AlarmEvent.Stopped) {
        if (event.alarmId == alarm?.id && !isFinishing) {
            finishActivity()
        }
    }

    private fun finishActivity() {
        finish()
        overridePendingTransition(0, 0)
    }
}
