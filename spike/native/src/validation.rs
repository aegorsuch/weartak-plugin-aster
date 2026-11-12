// SPDX-License-Identifier: GPL-3.0-or-later
use super::*;
use aster_node::{SoftwareZeroizationState, inspect_store, zeroize_node};

async fn zeroization(root: &Path, live: bool) -> Result<String, ProbeError> {
    let state = root.join(if live {
        "zeroize-live-state"
    } else {
        "zeroize-stopped-state"
    });
    let bundle = root.join(if live {
        "zeroize-live.bundle"
    } else {
        "zeroize-stopped.bundle"
    });
    if state.exists() {
        return Err("zeroization test requires fresh disposable state".into());
    }
    publish_and_query(
        state.clone(),
        &bundle,
        "weartak/spike",
        "jni-evaluation",
        true,
    )
    .await?;
    let before = inspect_store(&state)?;
    if before.events != 1 || before.event_sealed_bytes == 0 {
        return Err("synthetic Event baseline missing before zeroization".into());
    }
    let running = if live {
        Some(open_node(state.clone(), &bundle).await?)
    } else {
        None
    };
    let selected = running.as_ref().map(|node| node.selected_events());
    let receipt_result = zeroize_node(&state, &bundle, Duration::from_secs(15)).await;
    let shutdown = match running {
        Some(node) => node.shutdown().await.map(|_| ()).map_err(ProbeError::from),
        None => Ok(()),
    };
    let receipt = match (receipt_result, shutdown) {
        (Ok(receipt), Ok(())) => receipt,
        (Err(error), Ok(())) => return Err(error.into()),
        (Ok(_), Err(error)) => return Err(error),
        (Err(error), Err(shutdown)) => {
            return Err(format!("zeroization failed: {error}; shutdown: {shutdown}").into());
        }
    };
    if receipt.live_request != live
        || receipt.state != SoftwareZeroizationState::Complete
        || !receipt.mission_destroyed
        || !receipt.identity_destroyed
        || receipt.preserved.events != before.events
        || receipt.preserved.event_sealed_bytes != before.event_sealed_bytes
    {
        return Err(
            "zeroization receipt does not establish terminal cleanup and preserved Event".into(),
        );
    }
    if std::fs::metadata(&bundle)?.len() != 0
        || std::fs::metadata(state.join("identity.key"))?.len() != 0
    {
        return Err("zeroized fixture/identity not retained as zero-length tombstones".into());
    }
    if let Some(selected) = selected {
        let error = selected
            .status()
            .await
            .err()
            .ok_or("zeroized live facade still admitted status")?;
        if error.kind() != aster_node::application::ApplicationErrorKind::StateUnavailable {
            return Err(format!("unexpected zeroized facade error: {error}").into());
        }
    }
    let preserved = inspect_store(&state)?;
    if preserved.zeroization != SoftwareZeroizationState::Complete {
        return Err("terminal state not durable".into());
    }
    // The same terminal state must reject a still-valid, separate synthetic bundle.
    let lockout = match open_node(state.clone(), &root.join("other.bundle")).await {
        Ok(node) => {
            node.shutdown().await?;
            return Err("terminal state admitted a fresh mission fixture".into());
        }
        Err(error) => error.to_string(),
    };
    if !lockout.contains("terminally locked out in Complete state") {
        return Err(format!("wrong terminal admission rejection: {lockout}").into());
    }
    let replay = zeroize_node(&state, &bundle, Duration::from_secs(15)).await?;
    if replay.state != SoftwareZeroizationState::Complete
        || replay.preserved.events != before.events
        || replay.preserved.event_sealed_bytes != before.event_sealed_bytes
    {
        return Err("zeroization replay was not idempotent".into());
    }
    Ok(format!(
        "ZEROIZATION_{}_COMPLETE_EVENT_PRESERVED_REPLAY_LOCKOUT reason={lockout}",
        if live { "LIVE" } else { "STOPPED" }
    ))
}

async fn zeroization_rejects_wrong_authority(root: &Path) -> Result<String, ProbeError> {
    let state = root.join("zeroize-wrong-authority-state");
    let mission = root.join("reference.bundle");
    let wrong_mission = root.join("other-authority.bundle");
    if state.exists() {
        return Err("wrong-authority test requires fresh disposable state".into());
    }
    let mission_len = std::fs::metadata(&mission)?.len();
    let wrong_mission_len = std::fs::metadata(&wrong_mission)?.len();
    if UnprotectedReferenceMission::load(&mission)?.mission_authority_id()
        == UnprotectedReferenceMission::load(&wrong_mission)?.mission_authority_id()
    {
        return Err("wrong-authority fixture has the same mission authority".into());
    }
    let event_id = publish_and_query(
        state.clone(),
        &mission,
        "weartak/spike",
        "jni-evaluation",
        true,
    )
    .await?;
    let before = inspect_store(&state)?;
    let identity_path = state.join("identity.key");
    let identity_len = std::fs::metadata(&identity_path)?.len();
    if before.events != 1
        || before.event_sealed_bytes == 0
        || mission_len == 0
        || wrong_mission_len == 0
        || identity_len == 0
    {
        return Err("wrong-authority test lacks live synthetic baseline".into());
    }

    let node = open_node(state.clone(), &mission).await?;
    let live_rejection = zeroize_node(&state, &wrong_mission, Duration::from_secs(5)).await;
    let live_error = match live_rejection {
        Ok(_) => {
            let _ = node.shutdown().await;
            return Err("live zeroization accepted a different mission fixture".into());
        }
        Err(error) if error.to_string().to_ascii_lowercase().contains("mission") => error,
        Err(error) => {
            let shutdown = node.shutdown().await;
            return Err(format!("wrong live rejection: {error}; shutdown: {shutdown:?}").into());
        }
    };
    find_event(
        &node,
        Scope::new("weartak/spike")?,
        Topic::new("jni-evaluation")?,
        &event_id,
        None,
    )
    .await?;
    node.shutdown().await?;
    let after_live = inspect_store(&state)?;
    if after_live.zeroization != SoftwareZeroizationState::Live
        || after_live.events != before.events
        || after_live.event_sealed_bytes != before.event_sealed_bytes
        || std::fs::metadata(&mission)?.len() != mission_len
        || std::fs::metadata(&wrong_mission)?.len() != wrong_mission_len
        || std::fs::metadata(&identity_path)?.len() != identity_len
    {
        return Err("live mismatch changed state or artifacts".into());
    }

    let stopped_rejection = zeroize_node(&state, &wrong_mission, Duration::from_secs(5)).await;
    let stopped_error = match stopped_rejection {
        Ok(_) => return Err("stopped zeroization accepted a different mission fixture".into()),
        Err(error) if error.to_string().to_ascii_lowercase().contains("mission") => error,
        Err(error) => return Err(format!("wrong stopped rejection: {error}").into()),
    };
    let after_stopped = inspect_store(&state)?;
    if after_stopped.zeroization != SoftwareZeroizationState::Live
        || after_stopped.events != before.events
        || after_stopped.event_sealed_bytes != before.event_sealed_bytes
        || std::fs::metadata(&mission)?.len() != mission_len
        || std::fs::metadata(&wrong_mission)?.len() != wrong_mission_len
        || std::fs::metadata(&identity_path)?.len() != identity_len
    {
        return Err("stopped mismatch changed state or artifacts".into());
    }

    let completed = zeroize_node(&state, &mission, Duration::from_secs(5)).await?;
    if completed.state != SoftwareZeroizationState::Complete
        || completed.preserved.events != before.events
        || completed.preserved.event_sealed_bytes != before.event_sealed_bytes
        || std::fs::metadata(&mission)?.len() != 0
        || std::fs::metadata(&identity_path)?.len() != 0
    {
        return Err("correct mission could not complete cleanup after rejection".into());
    }
    Ok(format!(
        "ZEROIZATION_WRONG_AUTHORITY_LIVE_AND_STOPPED_REFUSED_THEN_CORRECT_COMPLETE live={live_error}; stopped={stopped_error}"
    ))
}

async fn inspect_terminal(root: &Path, live: bool) -> Result<String, ProbeError> {
    let state = root.join(if live {
        "zeroize-live-state"
    } else {
        "zeroize-stopped-state"
    });
    let bundle = root.join(if live {
        "zeroize-live.bundle"
    } else {
        "zeroize-stopped.bundle"
    });
    let preserved = inspect_store(&state)?;
    if preserved.zeroization != SoftwareZeroizationState::Complete
        || preserved.events != 1
        || preserved.event_sealed_bytes == 0
        || std::fs::metadata(&bundle)?.len() != 0
        || std::fs::metadata(state.join("identity.key"))?.len() != 0
    {
        return Err("terminal cleanup evidence did not survive process restart".into());
    }
    let error = match open_node(state.clone(), &root.join("other.bundle")).await {
        Ok(node) => {
            node.shutdown().await?;
            return Err("terminal restart accepted".into());
        }
        Err(error) => error,
    };
    if !error
        .to_string()
        .contains("terminally locked out in Complete state")
    {
        return Err(format!("unexpected terminal restart error: {error}").into());
    }
    let replay = zeroize_node(&state, &bundle, Duration::from_secs(15)).await?;
    if replay.state != SoftwareZeroizationState::Complete
        || replay.preserved.events != 1
        || replay.preserved.event_sealed_bytes != preserved.event_sealed_bytes
    {
        return Err("terminal replay failed after process restart".into());
    }
    Ok("ZEROIZATION_PROCESS_RESTART_COMPLETE_TOMBSTONES_LOCKOUT_REPLAY".into())
}

async fn recover_interrupted_zeroization(root: &Path) -> Result<String, ProbeError> {
    let state = root.join("zeroize-live-state");
    let bundle = root.join("zeroize-live.bundle");
    let marker = std::fs::read_to_string(root.join("zeroize-interruption-marker"))?;
    if marker.trim() != "mission=0 identity=32" {
        return Err(format!("unexpected interruption marker: {marker}").into());
    }
    let pre_state = inspect_store(&state)
        .map(|inspection| format!("{:?}", inspection.zeroization))
        .unwrap_or_else(|error| format!("unreadable-before-recovery:{error}"));
    let receipt = zeroize_node(&state, &bundle, Duration::from_secs(15)).await?;
    if receipt.state != SoftwareZeroizationState::Complete
        || receipt.preserved.events != 1
        || receipt.preserved.event_sealed_bytes == 0
        || std::fs::metadata(&bundle)?.len() != 0
        || std::fs::metadata(state.join("identity.key"))?.len() != 0
    {
        return Err("interrupted cleanup did not resume to terminal state".into());
    }
    Ok(format!(
        "ZEROIZATION_INTERRUPTED_RESUMED marker={} pre_state={} events={} sealed_bytes={}",
        marker.trim(),
        pre_state,
        receipt.preserved.events,
        receipt.preserved.event_sealed_bytes
    ))
}

async fn independent_read(root: &Path, event: &str) -> Result<String, ProbeError> {
    let node = open_node(root.join("state"), &root.join("reference.bundle")).await?;
    let result: Result<String, ProbeError> = async {
        find_event(
            &node,
            Scope::new("weartak/spike")?,
            Topic::new("jni-evaluation")?,
            event,
            None,
        )
        .await?;
        let events = node.selected_events();
        let mut journal = numbered::Journal::open(
            &root.join("state").join("independent-consumer.redb"),
            b"weartak.independent.consumer",
        )?;
        journal
            .recover(&mut numbered::Backend::Live(&events))
            .await?;
        let client = journal.client_context()?;
        let subscription = events
            .subscribe(EventSubscriptionRequest {
                client: client.clone(),
                operation_key: b"independent-subscription".to_vec(),
                topic: Topic::new("jni-evaluation")?,
                scope: Scope::new("weartak/spike")?,
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
            return Err("independent receiver acknowledgement lost after restart".into());
        }
        Ok("INDEPENDENT_APP_RECEIVER_PROCESS_RESTART_EVENT_ACK".into())
    }
    .await;
    let shutdown = node.shutdown().await;
    match (result, shutdown) {
        (Ok(receipt), Ok(_)) => Ok(receipt),
        (Err(error), Ok(_)) => Err(error),
        (Ok(_), Err(error)) => Err(error.into()),
        (Err(error), Err(shutdown)) => {
            Err(format!("independent restart failed: {error}; shutdown: {shutdown}").into())
        }
    }
}

async fn independent_peer(
    root: &Path,
    peer: &str,
    bind: SocketAddr,
    receiver: bool,
    event_id: &str,
) -> Result<String, ProbeError> {
    let mission = UnprotectedReferenceMission::load(root.join("reference.bundle"))?;
    let peer: MissionExpectedPeer = peer.parse()?;
    let expected_mission = peer.mission;
    let state = root.join("state");
    let node = start_node(NodeConfig {
        state: state.clone(),
        bind,
        mission,
        peers: vec![peer],
        mutable_interests: Default::default(),
        sync_interval: Duration::from_millis(250),
        run_for: None,
        application: NodeApplication::Relay,
    })
    .await?;
    let events = node.selected_events();
    let operation: Result<String, ProbeError> = async {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(90);
        if receiver {
            let journal_path = state.join("independent-consumer.redb");
            if !journal_path.exists() {
                numbered::Journal::initialize(&journal_path, b"weartak.independent.consumer")?;
            }
            let mut journal =
                numbered::Journal::open(&journal_path, b"weartak.independent.consumer")?;
            journal
                .recover(&mut numbered::Backend::Live(&events))
                .await?;
            let client = journal.client_context()?;
            let subscription = events
                .subscribe(EventSubscriptionRequest {
                    client: client.clone(),
                    operation_key: b"independent-subscription".to_vec(),
                    topic: Topic::new("jni-evaluation")?,
                    scope: Scope::new("weartak/spike")?,
                    include_descendant_scopes: false,
                })
                .await?;
            std::fs::write(root.join("ready"), b"durable-subscription")?;
            loop {
                let page = events
                    .poll(EventPollRequest {
                        client: client.clone(),
                        subscription: subscription.id,
                        delivery_limit: 128,
                        scan_limit: 128,
                    })
                    .await?;
                let status = events.status().await?;
                if let Some(delivery) = page.deliveries.iter().find(|delivery| {
                    delivery.event.id.to_string() == event_id
                        && delivery.event.payload == b"weartak-spike-event"
                }) && status.authenticated_contacts > 0
                    && status.peers.iter().any(|peer| {
                        peer.peer == expected_mission
                            && peer.authorization == PeerAuthorization::Active
                    })
                {
                    events
                        .acknowledge(client.clone(), subscription.id, delivery.event.id)
                        .await?;
                    std::fs::write(root.join("received-event"), event_id)?;
                    return Ok(format!(
                        "INDEPENDENT_RECEIVER_EVENT_ACK contacts={}",
                        status.authenticated_contacts
                    ));
                }
                if tokio::time::Instant::now() >= deadline {
                    return Err(format!(
                        "independent receiver deadline contacts={} failed={} sync={:?}",
                        status.authenticated_contacts, status.failed_contact_attempts, status.sync
                    )
                    .into());
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        } else {
            // Keep endpoint alive for bounded receiver poll/ack after first completed contact.
            let mut completed = None;
            loop {
                let status = events.status().await?;
                if status.authenticated_contacts > 0 {
                    completed.get_or_insert(tokio::time::Instant::now());
                }
                if completed.is_some_and(|time| time.elapsed() >= Duration::from_secs(5)) {
                    return Ok(format!(
                        "INDEPENDENT_PUBLISHER_CONTACTS={}",
                        status.authenticated_contacts
                    ));
                }
                if tokio::time::Instant::now() >= deadline {
                    return Err(format!(
                        "independent publisher deadline contacts={} failed={} sync={:?}",
                        status.authenticated_contacts, status.failed_contact_attempts, status.sync
                    )
                    .into());
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
    .await;
    let shutdown = node.shutdown().await;
    match (operation, shutdown) {
        (Ok(receipt), Ok(_)) => Ok(receipt),
        (Err(error), Ok(_)) => Err(error),
        (Ok(_), Err(error)) => Err(error.into()),
        (Err(error), Err(shutdown)) => {
            Err(format!("independent probe failed: {error}; shutdown: {shutdown}").into())
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_describePeer(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring, ProbeError> {
        let root: String = env.get_string(&root)?.into();
        let root = Path::new(&root);
        let carrier = NodeIdentity::load_or_create(root.join("state"))?.id();
        let mission = UnprotectedReferenceMission::load(root.join("reference.bundle"))?.identity();
        Ok(env
            .new_string(format!("{carrier}={}", aster_node::format_node_id(mission)))?
            .into_raw())
    }));
    return_result(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_runIndependentPeer(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    peer: JString,
    bind: JString,
    receiver: jboolean,
    event_id: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring, ProbeError> {
        let root: String = env.get_string(&root)?.into();
        let peer: String = env.get_string(&peer)?.into();
        let bind: String = env.get_string(&bind)?.into();
        let event_id: String = env.get_string(&event_id)?.into();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()?;
        let receipt = runtime.block_on(independent_peer(
            Path::new(&root),
            &peer,
            bind.parse()?,
            receiver != 0,
            &event_id,
        ))?;
        Ok(env.new_string(receipt)?.into_raw())
    }));
    return_result(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_weartak_plugin_aster_spike_AsterProbe_validateSecurity(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    mode: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jstring, ProbeError> {
        let root: String = env.get_string(&root)?.into();
        let mode: String = env.get_string(&mode)?.into();
        let root = Path::new(&root);
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()?;
        let receipt = runtime.block_on(async {
            match mode.as_str() {
                "carrier" | "mission" => {
                    reconnect(
                        root,
                        &root.join("reference.bundle"),
                        &root.join("receiver.bundle"),
                        std::fs::read_to_string(root.join("event-id"))?.trim(),
                        Some(&mode),
                    )
                    .await
                }
                "zeroize-live" | "zeroize-stopped" => {
                    zeroization(root, mode == "zeroize-live").await
                }
                "zeroize-wrong-authority" => zeroization_rejects_wrong_authority(root).await,
                "inspect-zeroize-live" | "inspect-zeroize-stopped" => {
                    inspect_terminal(root, mode == "inspect-zeroize-live").await
                }
                "inspect-interrupted-zeroize" => recover_interrupted_zeroization(root).await,
                "independent-read" => {
                    independent_read(
                        root,
                        std::fs::read_to_string(root.join("received-event"))?.trim(),
                    )
                    .await
                }
                _ => Err("unknown synthetic security test".into()),
            }
        })?;
        Ok(env.new_string(receipt)?.into_raw())
    }));
    return_result(&mut env, result)
}
