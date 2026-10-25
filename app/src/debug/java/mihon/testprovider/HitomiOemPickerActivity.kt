package mihon.testprovider

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Debug-only neutral picker host. It never reads or writes the application's storage preference. */
class HitomiOemPickerActivity : ComponentActivity() {
    private lateinit var session: Session
    private var launched by mutableStateOf(false)
    private val picker = registerForActivityResult(object : ActivityResultContract<Uri?, PickResult>() {
        private val delegate = ActivityResultContracts.OpenDocumentTree()

        override fun createIntent(context: Context, input: Uri?): Intent = delegate.createIntent(context, input)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        override fun parseResult(resultCode: Int, intent: Intent?) = PickResult(
            delegate.parseResult(resultCode, intent),
            if (resultCode == Activity.RESULT_OK) intent?.flags ?: 0 else 0,
        )
    }) { result ->
        session.result.complete(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "app.yomitori.yakuyomi.audit") { "ISOLATED_AUDIT_REQUIRED" }
        session = checkNotNull(active.get()) { "PICKER_SESSION_MISSING" }
        check(intent.getStringExtra(EXTRA_SESSION) == session.uuid.toString()) { "PICKER_SESSION_MISMATCH" }
        setContent {
            MaterialTheme {
                Column(Modifier.padding(24.dp)) {
                    Text("Bounded download audit")
                    Text("Select the controller's new empty folder in Internal storage / Download:")
                    Text(session.folderName)
                    Button(
                        onClick = {
                            launched = true
                            picker.launch(null)
                        },
                        enabled = !launched,
                    ) {
                        Text("Choose new audit folder")
                    }
                    Text("The audit stores at most two images and removes only recorded audit documents.")
                }
            }
        }
    }

    data class PickResult(val uri: Uri?, val flags: Int)

    class Session(val uuid: UUID) {
        val folderName = "hitomi-downloader-$uuid"
        val result = CompletableDeferred<PickResult>()
    }

    companion object {
        const val EXTRA_SESSION = "mihon.testprovider.HITOMI_OEM_SESSION"
        private val active = AtomicReference<Session?>()

        fun install(session: Session) {
            check(active.compareAndSet(null, session)) { "PICKER_SESSION_ALREADY_OWNED" }
        }

        fun uninstall(session: Session) {
            check(active.compareAndSet(session, null)) { "PICKER_SESSION_OWNER_CHANGED" }
        }
    }
}
