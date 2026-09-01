// Tauri command handlers must take owned values: `State<'_, _>` is the framework's
// preferred wrapper, and serde-deserialized payloads (Vec, String, ...) cannot be borrowed.
#![allow(clippy::needless_pass_by_value)]
#![allow(clippy::unused_async)]

use serde::Deserialize;
use tauri::{AppHandle, Runtime, State, command, plugin::PermissionState};

use crate::models::PushDiagnostics;
use crate::{NotificationData, Notifications, Result};

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct NotificationIdentifier {
    pub id: i32,
    #[allow(dead_code)]
    pub tag: Option<String>,
}

#[command]
pub async fn is_permission_granted<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<Option<bool>> {
    let state = notification.permission_state().await?;
    match state {
        PermissionState::Granted => Ok(Some(true)),
        PermissionState::Denied => Ok(Some(false)),
        PermissionState::Prompt | PermissionState::PromptWithRationale => Ok(None),
    }
}

#[command]
pub async fn request_permission<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<PermissionState> {
    notification.request_permission().await
}

#[command]
pub async fn register_for_push_notifications<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    vapid: Option<String>,
    provider: Option<String>,
    embedded_gateway_url: Option<String>,
    user_id: Option<String>,
    device_id: Option<String>,
) -> Result<crate::models::PushNotificationResponse> {
    #[cfg(mobile)]
    return notification
        .register_for_push_notifications(vapid, provider, embedded_gateway_url, user_id, device_id)
        .await;
    #[cfg(desktop)]
    {
        let _ = (provider, embedded_gateway_url, user_id, device_id);
        notification.register_for_push_notifications(vapid).await
    }
}

#[command]
pub async fn unregister_for_push_notifications<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<()> {
    #[cfg(all(desktop, target_os = "linux", feature = "push-notifications"))]
    {
        notification.unregister_for_push_notifications_async().await
    }
    #[cfg(mobile)]
    {
        notification.unregister_for_push_notifications().await
    }
    #[cfg(all(desktop, not(all(target_os = "linux", feature = "push-notifications"))))]
    {
        notification.unregister_for_push_notifications()
    }
}

#[cfg(all(
    feature = "push-notifications",
    any(all(desktop, target_os = "linux"), target_os = "android")
))]
#[command]
pub async fn list_distributors<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<Vec<String>> {
    notification.list_distributors().await
}

#[cfg(all(
    feature = "push-notifications",
    any(all(desktop, target_os = "linux"), target_os = "android")
))]
#[command]
pub async fn set_distributor<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    name: String,
) -> Result<()> {
    notification.set_distributor(name).await
}

#[cfg(all(
    feature = "push-notifications",
    any(all(desktop, target_os = "linux"), target_os = "android")
))]
#[command]
pub async fn set_token<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    token: String,
) -> Result<()> {
    notification.set_token(token).await
}

#[command]
pub async fn notify<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    options: NotificationData,
) -> Result<()> {
    let mut builder = notification.builder();
    builder.data = options;
    builder.show().await
}

#[command]
pub async fn register_action_types<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    types: Vec<crate::ActionType>,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.register_action_types(types).await;
    #[cfg(desktop)]
    return notification.register_action_types(types);
}

#[command]
pub async fn get_pending<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<Vec<crate::PendingNotification>> {
    notification.pending().await
}

#[command]
pub async fn get_active<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<Vec<crate::ActiveNotification>> {
    notification.active().await
}

#[command]
pub async fn set_click_listener_active<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    active: bool,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.set_click_listener_active(active).await;
    #[cfg(desktop)]
    return notification.set_click_listener_active(active);
}

#[command]
pub async fn set_action_listener_active<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    active: bool,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.set_action_listener_active(active).await;
    #[cfg(desktop)]
    return notification.set_action_listener_active(active);
}

#[command]
pub async fn set_push_message_listener_active<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    active: bool,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.set_push_message_listener_active(active).await;
    #[cfg(desktop)]
    return notification.set_push_message_listener_active(active);
}

#[command]
pub async fn set_encrypted_content_allowed<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    allowed: bool,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.set_encrypted_content_allowed(allowed).await;
    #[cfg(desktop)]
    return notification.set_encrypted_content_allowed(allowed);
}

#[command]
pub async fn take_push_diagnostics<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<PushDiagnostics> {
    #[cfg(mobile)]
    return notification.take_push_diagnostics().await;
    #[cfg(desktop)]
    return notification.take_push_diagnostics();
}

#[command]
#[cfg(mobile)]
pub async fn remove_active<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    notifications: Vec<NotificationIdentifier>,
) -> Result<()> {
    let ids: Vec<i32> = notifications.into_iter().map(|n| n.id).collect();
    notification.remove_active(ids).await
}

#[command]
#[cfg(desktop)]
pub fn remove_active<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    notifications: Vec<NotificationIdentifier>,
) -> Result<()> {
    let ids: Vec<i32> = notifications.into_iter().map(|n| n.id).collect();
    notification.remove_active(ids)
}

#[command]
#[cfg(mobile)]
pub async fn remove_all<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<()> {
    notification.remove_all_active().await
}

#[command]
#[cfg(desktop)]
pub fn remove_all<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<()> {
    notification.remove_all_active()
}

#[command]
pub async fn cancel<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    notifications: Vec<i32>,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.cancel(notifications).await;
    #[cfg(desktop)]
    return notification.cancel(notifications);
}

#[command]
pub async fn cancel_all<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.cancel_all().await;
    #[cfg(desktop)]
    return notification.cancel_all();
}

#[command]
pub async fn create_channel<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    channel: crate::Channel,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.create_channel(channel).await;
    #[cfg(desktop)]
    return notification.create_channel(channel);
}

#[command]
pub async fn delete_channel<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
    id: String,
) -> Result<()> {
    #[cfg(mobile)]
    return notification.delete_channel(id).await;
    #[cfg(desktop)]
    return notification.delete_channel(id);
}

#[command]
pub async fn list_channels<R: Runtime>(
    _app: AppHandle<R>,
    notification: State<'_, Notifications<R>>,
) -> Result<Vec<crate::Channel>> {
    #[cfg(mobile)]
    return notification.list_channels().await;
    #[cfg(desktop)]
    return notification.list_channels();
}
