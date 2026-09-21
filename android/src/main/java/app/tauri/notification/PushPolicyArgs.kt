package app.tauri.notification

import app.tauri.annotation.InvokeArg

@InvokeArg
class PushPolicyArgs {
    var enabled: Boolean = false
    var content: Boolean = false
    var encryptedContent: Boolean = false
    var sounds: Boolean = true
    var notifyOnce: Boolean = true
}
