package dev.blocks.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.blocks.android.api.BlocksApiClient
import dev.blocks.android.api.ServiceBlocksRepository
import dev.blocks.android.auth.GoogleIdentityClient
import dev.blocks.android.mvp.MvpController
import dev.blocks.android.mvp.MvpScreen
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.SessionContinuityStore
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val controller by lazy {
        MvpController(
            ServiceBlocksRepository(BlocksApiClient(URI(BuildConfig.BLOCKS_API_BASE_URL))),
            AndroidEncryptedDraftStore(this),
        )
    }
    private val googleIdentity by lazy { GoogleIdentityClient(BuildConfig.BLOCKS_GOOGLE_SERVER_CLIENT_ID) }
    private val continuity by lazy { SessionContinuityStore(this) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(246, 243, 234))
        }
        setContentView(content)
        render()
        if (continuity.shouldRestore) restoreSignIn()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(label(getString(R.string.heading), 28f, Color.rgb(32, 48, 42)))
        controller.state.error?.let { content.addView(label(it, 14f, Color.rgb(150, 40, 40))) }
        when (controller.state.screen) {
            MvpScreen.LOGIN -> renderLogin()
            MvpScreen.PROJECTS -> renderProjects()
            MvpScreen.PICKUP -> renderPickup()
            MvpScreen.PARKING -> renderParking()
        }
    }

    private fun renderLogin() {
        content.addView(label("Sign in to return to your work", 18f))
        content.addView(button("Sign in with Google") { signIn() })
        content.addView(label("Google proves your identity; Blocks controls project access.", 12f))
    }

    private fun renderProjects() {
        content.addView(label("Choose a project", 20f))
        controller.state.projects.forEach { project ->
            content.addView(button(project.name) { controller.selectProject(project.id); render() })
        }
        val projectName = input("New project name", controller.state.drafts.projectName) {
            controller.updateProjectNameDraft(it)
        }
        content.addView(projectName)
        content.addView(button("Create project") {
            runRepositoryAction { controller.createProject(projectName.text.toString()) }
        })
        content.addView(button("Sign out") { signOut() })
    }

    private fun renderPickup() {
        val project = requireNotNull(controller.state.selectedProject)
        content.addView(label(project.name, 20f))
        val parking = controller.state.parking
        if (parking == null) {
            content.addView(label("Nothing is parked yet. Choose the next useful action.", 16f))
        } else {
            content.addView(label("Where I am", 12f, Color.GRAY))
            content.addView(label(parking.location, 17f))
            content.addView(label("Exact next physical action", 12f, Color.GRAY))
            content.addView(label(parking.nextAction, 17f, Color.rgb(53, 104, 89)))
            content.addView(label("Must not forget", 12f, Color.GRAY))
            content.addView(label(parking.mustNotForget, 17f))
        }
        content.addView(button("Park this work") { controller.beginParking(); render() })
        content.addView(button("Choose another project") { controller.chooseAnotherProject(); render() })
        content.addView(button("Sign out") { signOut() })
    }

    private fun renderParking() {
        content.addView(label("Park at the boundary", 20f))
        val draft = controller.state.drafts.parking
        var locationValue = draft.location
        var nextActionValue = draft.nextAction
        var mustNotForgetValue = draft.mustNotForget
        fun persist() = controller.updateParkingDraft(
            ParkingDraft(locationValue, nextActionValue, mustNotForgetValue),
        )
        val location = input("Where am I?", locationValue) { locationValue = it; persist() }
        val nextAction = input("Exact next physical action", nextActionValue) { nextActionValue = it; persist() }
        val mustNotForget = input("What must I not forget?", mustNotForgetValue) {
            mustNotForgetValue = it
            persist()
        }
        content.addView(location)
        content.addView(nextAction)
        content.addView(mustNotForget)
        content.addView(button("Save parking record") {
            controller.park(location.text.toString(), nextAction.text.toString(), mustNotForget.text.toString())
            render()
        })
    }

    private fun label(text: String, size: Float, color: Int = Color.DKGRAY) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, 0, 0, dp(12))
    }

    private fun input(hint: String, initial: String = "", changed: (String) -> Unit = {}) = EditText(this).apply {
        this.hint = hint
        setText(initial)
        isSingleLine = false
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                changed(value?.toString().orEmpty())
            }
            override fun afterTextChanged(value: Editable?) = Unit
        })
    }

    private fun button(text: String, action: (View) -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener(action)
    }

    private fun signIn() {
        uiScope.launch {
            try {
                val token = googleIdentity.signIn(this@MainActivity)
                withContext(Dispatchers.IO) { controller.login(token) }
                continuity.shouldRestore = true
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                controller.reportError(error.message ?: "Sign-in failed. Try again.")
            }
            render()
        }
    }

    private fun restoreSignIn() {
        uiScope.launch {
            try {
                val token = googleIdentity.restore(this@MainActivity)
                withContext(Dispatchers.IO) { controller.login(token) }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                continuity.shouldRestore = false
                controller.reportError("Please sign in again to continue.")
            }
            render()
        }
    }

    private fun signOut() {
        uiScope.launch {
            continuity.shouldRestore = false
            runCatching { googleIdentity.clear(this@MainActivity) }
            controller.logout()
            render()
        }
    }

    private fun runRepositoryAction(action: () -> Unit) {
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: Exception) {
                controller.reportError(error.message ?: "Blocks could not complete that request.")
            }
            render()
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
