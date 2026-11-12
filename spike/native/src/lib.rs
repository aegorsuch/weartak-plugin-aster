// SPDX-License-Identifier: Apache-2.0
// Selected Event lifecycle adapted from Aster's live_event_application.rs,
// Apache-2.0, https://github.com/defenseunicorns/aster.
use std::{
    error::Error,
    net::SocketAddr,
    panic::{AssertUnwindSafe, catch_unwind},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};

use aster_node::{
    MissionExpectedPeer, NodeApplication, NodeConfig, NodeIdentity, RunningNode,
    application::{
        EventPollRequest, EventQuery, EventSubscriptionRequest, PeerAuthorization, Priority, Scope,
        Topic,
    },
    mission::UnprotectedReferenceMission,
    publication_journal as numbered, start_node,
};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jstring},
};

type ProbeError = Box<dyn Error + Send + Sync>;

mod validation;

static VERIFIER_INITIALIZED: AtomicBool = AtomicBool::new(false);

#[cfg(target_os = "android")]
static ANDROID_CONTEXT: once_cell::sync::OnceCell<
    jni22::refs::Global<jni22::objects::JObject<'static>>,
> = once_cell::sync::OnceCell::new();

async fn open_node(state: PathBuf, bundle: &Path) -> Result<RunningNode, ProbeError> {
    if !VERIFIER_INITIALIZED.load(Ordering::Acquire) {
        return Err("Android certificate verifier has not been initialized".into());
    }
    let mission = UnprotectedReferenceMission::load(bundle)?;
    Ok(start_node(NodeConfig {
        state,
        bind: SocketAddr::from(([127, 0, 0, 1], 0)),
        mission,
        peers: Vec::new(),
        mutable_interests: Default::default(),
        sync_interval: Duration::from_millis(250),
        run_for: None,
        application: NodeApplication::Relay,
    })
    .await?)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_initialize(
    mut env: jni22::EnvUnowned,
    _class: jni22::objects::JClass,
    context: jni22::objects::JObject,
) {
    env.with_env(|env| {
        ANDROID_CONTEXT.get_or_try_init(|| {
            let application = env.new_global_ref(&context)?;
            let vm = env.get_java_vm()?;
            rustls_platform_verifier::android::init_with_env(env, context)?;
            // Both pointers remain valid for the process lifetime through the static global reference.
            unsafe {
                iroh::dns::install_android_jni_context(
                    vm.get_raw().cast(),
                    application.as_obj().as_raw().cast(),
                );
            }
            Ok::<_, jni22::errors::Error>(application)
        })?;
        VERIFIER_INITIALIZED.store(true, Ordering::Release);
        Ok::<(), jni22::errors::Error>(())
    })
    .resolve::<jni22::errors::ThrowRuntimeExAndDefault>();
}

async fn find_event(
    running: &RunningNode,
    scope: Scope,
    topic: Topic,
    expected_id: &str,
    publisher: Option<[u8; 32]>,
) -> Result<(), ProbeError> {
    let events = running.selected_events();
    let mut marker = 0;
    loop {
        let page = events
            .query(EventQuery {
                publisher,
                topic: Some(topic.clone()),
                scope: Some(scope.clone()),
                logical_key: Some(b"weartak-spike".to_vec()),
                after_acceptance_marker: marker,
                limit: 128,
                ..EventQuery::default()
            })
            .await?;
        if page.items.iter().any(|item| {
            item.id.to_string() == expected_id && item.payload == b"weartak-spike-event"
        }) {
            return Ok(());
        }
        if !page.has_more || page.scanned_through <= marker {
            return Err("Event ID and payload not found in local query".into());
        }
        marker = page.scanned_through;
    }
}

async fn publish_and_query(
    state: PathBuf,
    bundle: &Path,
    scope: &str,
    topic: &str,
    initialize: bool,
) -> Result<String, ProbeError> {
    std::fs::create_dir_all(&state)?;
    let journal_path = state.join("weartak-spike-publication.redb");
    if initialize {
        numbered::Journal::initialize(&journal_path, b"weartak.aster.spike.v1")?;
    }
    let mut journal = numbered::Journal::open(&journal_path, b"weartak.aster.spike.v1")?;
    let scope = Scope::new(scope)?;
    let topic = Topic::new(topic)?;
    let running = open_node(state, bundle).await?;
    let result: Result<String, ProbeError> = async {
        let events = running.selected_events();
        journal
            .recover(&mut numbered::Backend::Live(&events))
            .await?;
        let intent = numbered::Intent {
            predecessor: None,
            topic: topic.as_str().into(),
            scope: scope.as_str().into(),
            priority: Priority::Priority as u8,
            logical_key: b"weartak-spike".to_vec(),
            payload: b"weartak-spike-event".to_vec(),
            tombstone: false,
            ttl_ms: None,
        };
        if let Some((_, pending)) = journal.pending()
            && pending != &intent
        {
            return Err("retained publication intent requires repair".into());
        }
        let outcome = journal
            .publish(&mut numbered::Backend::Live(&events), intent.clone())
            .await?;
        let publication = numbered::Backend::Live(&events)
            .metadata(&intent, &outcome)
            .await?;
        find_event(
            &running,
            scope,
            topic,
            &publication.id.to_string(),
            Some(events.identity()),
        )
        .await?;
        journal
            .acknowledge(&mut numbered::Backend::Live(&events))
            .await?;
        Ok(publication.id.to_string())
    }
    .await;
    let shutdown = running.shutdown().await;
    match (result, shutdown) {
        (Ok(event_id), Ok(_)) => Ok(event_id),
        (Err(error), Ok(_)) => Err(error),
        (Ok(_), Err(error)) => Err(error.into()),
        (Err(error), Err(shutdown)) => {
            Err(format!("probe failed: {error}; shutdown failed: {shutdown}").into())
        }
    }
}

async fn read_after_restart(
    state: PathBuf,
    bundle: &Path,
    scope: &str,
    topic: &str,
    event_id: &str,
    own_publisher: bool,
) -> Result<String, ProbeError> {
    let scope = Scope::new(scope)?;
    let topic = Topic::new(topic)?;
    let running = open_node(state.clone(), bundle).await?;
    let result: Result<(), ProbeError> = async {
        find_event(
            &running,
            scope.clone(),
            topic.clone(),
            event_id,
            own_publisher.then(|| running.selected_events().identity()),
        )
        .await?;
        if !own_publisher {
            let mut journal = numbered::Journal::open(
                &state.join("consumer-journal.redb"),
                b"weartak.reconnect.consumer",
            )?;
            let events = running.selected_events();
            journal
                .recover(&mut numbered::Backend::Live(&events))
                .await?;
            let client = journal.client_context()?;
            let subscription = events
                .subscribe(EventSubscriptionRequest {
                    client: client.clone(),
                    operation_key: b"weartak.reconnect.subscription".to_vec(),
                    topic,
                    scope,
                    include_descendant_scopes: false,
                })
                .await?;
            if !events
                .poll(EventPollRequest {
                    client,
                    subscription: subscription.id,
                    delivery_limit: 128,
                    scan_limit: 128,
                })
                .await?
                .deliveries
                .is_empty()
            {
                return Err("receiver acknowledgement did not survive process restart".into());
            }
        }
        Ok(())
    }
    .await;
    let shutdown = running.shutdown().await;
    match (result, shutdown) {
        (Ok(()), Ok(_)) => Ok(event_id.to_owned()),
        (Err(error), Ok(_)) => Err(error),
        (Ok(()), Err(error)) => Err(error.into()),
        (Err(error), Err(shutdown)) => {
            Err(format!("query failed: {error}; shutdown failed: {shutdown}").into())
        }
    }
}

async fn reconnect(
    root: &Path,
    publisher_bundle: &Path,
    receiver_bundle: &Path,
    event_id: &str,
    rejected_identity: Option<&str>,
) -> Result<String, ProbeError> {
    let publisher_state = root.join("state");
    let receiver_state = root.join(match rejected_identity {
        Some("carrier") => "rejected-carrier-state",
        Some("mission") => "rejected-mission-state",
        Some(_) => return Err("unknown rejected identity probe".into()),
        None => "receiver-state",
    });
    let publisher_mission = UnprotectedReferenceMission::load(publisher_bundle)?;
    let receiver_mission = UnprotectedReferenceMission::load(receiver_bundle)?;
    let publisher_id = NodeIdentity::load_existing(&publisher_state)?.id();
    let receiver_id = NodeIdentity::load_or_create(&receiver_state)?.id();
    let publisher_socket = std::net::UdpSocket::bind("127.0.0.1:0")?;
    let receiver_socket = std::net::UdpSocket::bind("127.0.0.1:0")?;
    let publisher_address = publisher_socket.local_addr()?;
    let receiver_address = receiver_socket.local_addr()?;
    let publisher_peer: MissionExpectedPeer = format!(
        "{receiver_id}@{receiver_address}={}",
        aster_node::format_node_id(receiver_mission.identity())
    )
    .parse()?;
    let expected_carrier = if rejected_identity == Some("carrier") {
        NodeIdentity::load_or_create(root.join("other-carrier-state"))?
            .id()
            .to_string()
    } else {
        publisher_id.to_string()
    };
    let expected_mission = if rejected_identity == Some("mission") {
        UnprotectedReferenceMission::load(root.join("other.bundle"))?.identity()
    } else {
        publisher_mission.identity()
    };
    let receiver_peer: MissionExpectedPeer = format!(
        "{expected_carrier}@{publisher_address}={}",
        aster_node::format_node_id(expected_mission)
    )
    .parse()?;
    drop(receiver_socket);
    let receiver = start_node(NodeConfig {
        state: receiver_state.clone(),
        bind: receiver_address,
        mission: receiver_mission,
        peers: vec![receiver_peer],
        mutable_interests: Default::default(),
        sync_interval: Duration::from_millis(250),
        run_for: None,
        application: NodeApplication::Relay,
    })
    .await?;
    let publisher_identity = publisher_mission.identity();
    let result: Result<(), ProbeError> = async {
        let journal_path = receiver_state.join("consumer-journal.redb");
        if !journal_path.exists() {
            numbered::Journal::initialize(&journal_path, b"weartak.reconnect.consumer")?;
        }
        let mut journal = numbered::Journal::open(&journal_path, b"weartak.reconnect.consumer")?;
        let events = receiver.selected_events();
        journal.recover(&mut numbered::Backend::Live(&events)).await?;
        let client = journal.client_context()?;
        let subscription = events.subscribe(EventSubscriptionRequest {
            client: client.clone(),
            operation_key: b"weartak.reconnect.subscription".to_vec(),
            topic: Topic::new("jni-evaluation")?,
            scope: Scope::new("weartak/spike")?,
            include_descendant_scopes: false,
        }).await?;
        drop(publisher_socket);
        let publisher = start_node(NodeConfig {
            state: publisher_state,
            bind: publisher_address,
            mission: publisher_mission,
            peers: vec![publisher_peer],
            mutable_interests: Default::default(),
            sync_interval: Duration::from_millis(250),
            run_for: None,
            application: NodeApplication::Relay,
        })
        .await?;
        let transfer: Result<(), ProbeError> = async {
            let deadline = tokio::time::Instant::now()
                + Duration::from_secs(if rejected_identity.is_some() { 20 } else { 120 });
            loop {
                let page = events
                    .poll(EventPollRequest {
                        client: client.clone(),
                        subscription: subscription.id,
                        delivery_limit: 128,
                        scan_limit: 128,
                    })
                    .await?;
                if let Some(delivery) = page.deliveries.iter().find(|item| {
                    item.event.id.to_string() == event_id && item.event.payload == b"weartak-spike-event"
                }) {
                    if rejected_identity.is_some() {
                        return Err("rejected peer delivered protected Event".into());
                    }
                    let status = events.status().await?;
                    if status.authenticated_contacts > 0
                        && status.peers.iter().any(|peer| {
                            peer.peer == publisher_identity && peer.authorization == PeerAuthorization::Active
                        })
                    {
                        events.acknowledge(client.clone(), subscription.id, delivery.event.id).await?;
                        if !events.poll(EventPollRequest {
                            client: client.clone(),
                            subscription: subscription.id,
                            delivery_limit: 128,
                            scan_limit: 128,
                        }).await?.deliveries.is_empty() {
                            return Err("acknowledged Event redelivered unexpectedly".into());
                        }
                        return Ok(());
                    }
                }
                if tokio::time::Instant::now() >= deadline {
                    let receiver_status = events.status().await?;
                    let publisher_status = publisher.selected_events().status().await?;
                    if rejected_identity.is_some() {
                        if receiver_status.authenticated_contacts != 0
                            || receiver_status.failed_contact_attempts == 0
                        {
                            return Err(format!(
                                "negative identity test inconclusive: contacts={} failed={} sync={:?}",
                                receiver_status.authenticated_contacts,
                                receiver_status.failed_contact_attempts,
                                receiver_status.sync,
                            ).into());
                        }
                        let page = events.query(EventQuery {
                            topic: Some(Topic::new("jni-evaluation")?),
                            scope: Some(Scope::new("weartak/spike")?),
                            limit: 128,
                            ..EventQuery::default()
                        }).await?;
                        if !page.items.is_empty() {
                            return Err("rejected peer retained protected Events".into());
                        }
                        return Ok(());
                    }
                    return Err(format!(
                        "authenticated reconnection deadline: receiver sync={:?} contacts={} failed={}; publisher sync={:?} contacts={} failed={}",
                        receiver_status.sync, receiver_status.authenticated_contacts, receiver_status.failed_contact_attempts,
                        publisher_status.sync, publisher_status.authenticated_contacts, publisher_status.failed_contact_attempts,
                    ).into());
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
        .await;
        let shutdown = publisher.shutdown().await;
        match (transfer, shutdown) {
            (Ok(()), Ok(_)) => Ok(()),
            (Err(error), Ok(_)) => Err(error),
            (Ok(()), Err(error)) => Err(error.into()),
            (Err(error), Err(shutdown)) => Err(format!(
                "transfer failed: {error}; publisher shutdown failed: {shutdown}"
            )
            .into()),
        }
    }
    .await;
    let shutdown = receiver.shutdown().await;
    match (result, shutdown) {
        (Ok(()), Ok(_)) => {}
        (Err(error), Ok(_)) => return Err(error),
        (Ok(()), Err(error)) => return Err(error.into()),
        (Err(error), Err(shutdown)) => {
            return Err(
                format!("transfer failed: {error}; receiver shutdown failed: {shutdown}").into(),
            );
        }
    }
    if let Some(identity) = rejected_identity {
        return Ok(format!("REJECTED_{identity}_NO_CONTACT_NO_EVENT"));
    }
    let restarted = open_node(receiver_state, receiver_bundle).await?;
    let retained = find_event(
        &restarted,
        Scope::new("weartak/spike")?,
        Topic::new("jni-evaluation")?,
        event_id,
        Some(publisher_identity),
    )
    .await;
    let shutdown = restarted.shutdown().await;
    match (retained, shutdown) {
        (Ok(()), Ok(_)) => Ok(event_id.to_owned()),
        (Err(error), Ok(_)) => Err(error),
        (Ok(()), Err(error)) => Err(error.into()),
        (Err(error), Err(shutdown)) => {
            Err(format!("retention failed: {error}; shutdown failed: {shutdown}").into())
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_reconnect(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    publisher_bundle: JString,
    receiver_bundle: JString,
    event_id: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring, ProbeError> {
        let root: String = env.get_string(&root)?.into();
        let publisher_bundle: String = env.get_string(&publisher_bundle)?.into();
        let receiver_bundle: String = env.get_string(&receiver_bundle)?.into();
        let event_id: String = env.get_string(&event_id)?.into();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()?;
        let receipt = runtime.block_on(reconnect(
            Path::new(&root),
            Path::new(&publisher_bundle),
            Path::new(&receiver_bundle),
            &event_id,
            None,
        ))?;
        Ok(env.new_string(receipt)?.into_raw())
    }));
    return_result(&mut env, result)
}

fn return_result(
    env: &mut JNIEnv,
    result: Result<Result<jstring, ProbeError>, Box<dyn std::any::Any + Send>>,
) -> jstring {
    match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            }
            std::ptr::null_mut()
        }
        Err(_) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    "native Aster probe panicked",
                );
            }
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_configureControlDirectory(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring, ProbeError> {
        let directory: String = env.get_string(&directory)?.into();
        #[cfg(all(target_os = "android", feature = "android-control-evaluation"))]
        aster_node::initialize_android_control_directory(Path::new(&directory))?;
        #[cfg(not(all(target_os = "android", feature = "android-control-evaluation")))]
        {
            let _ = directory;
            Err("explicit Android control-directory evaluation build required".into())
        }
        #[cfg(all(target_os = "android", feature = "android-control-evaluation"))]
        Ok(env
            .new_string("ANDROID_CONTROL_DIRECTORY_PINNED")?
            .into_raw())
    }));
    return_result(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_publishAndQuery(
    mut env: JNIEnv,
    _class: JClass,
    state: JString,
    bundle: JString,
    scope: JString,
    topic: JString,
    initialize: jboolean,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring, ProbeError> {
        let state: String = env.get_string(&state)?.into();
        let bundle: String = env.get_string(&bundle)?.into();
        let scope: String = env.get_string(&scope)?.into();
        let topic: String = env.get_string(&topic)?.into();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()?;
        let event_id = runtime.block_on(publish_and_query(
            PathBuf::from(state),
            Path::new(&bundle),
            &scope,
            &topic,
            initialize != 0,
        ))?;
        Ok(env.new_string(event_id)?.into_raw())
    }));
    return_result(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_readAfterRestart(
    mut env: JNIEnv,
    _class: JClass,
    state: JString,
    bundle: JString,
    scope: JString,
    topic: JString,
    event_id: JString,
    own_publisher: jboolean,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring, ProbeError> {
        let state: String = env.get_string(&state)?.into();
        let bundle: String = env.get_string(&bundle)?.into();
        let scope: String = env.get_string(&scope)?.into();
        let topic: String = env.get_string(&topic)?.into();
        let event_id: String = env.get_string(&event_id)?.into();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()?;
        let result = runtime.block_on(read_after_restart(
            PathBuf::from(state),
            Path::new(&bundle),
            &scope,
            &topic,
            &event_id,
            own_publisher != 0,
        ))?;
        Ok(env.new_string(result)?.into_raw())
    }));
    return_result(&mut env, result)
}
