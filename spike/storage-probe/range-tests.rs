// SPDX-License-Identifier: GPL-3.0-or-later
mod range_lock {
    include!(env!("REDB_RANGE_LOCK_SOURCE"));
}

use range_lock::RangeLock;
use std::{fs, io, time::Duration};

#[test]
fn shared_ranges_queries_validation_and_blocking() -> io::Result<()> {
    let path = std::env::temp_dir().join(format!("redb-ranges-{}", std::process::id()));
    let first = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create_new(true)
        .open(&path)?;
    let second = fs::OpenOptions::new().read(true).write(true).open(&path)?;
    let third = fs::OpenOptions::new().read(true).write(true).open(&path)?;

    assert!(first.try_lock_shared_range(10..20)?);
    assert!(second.try_lock_shared_range(10..20)?);
    assert!(!third.try_lock_range(10..20)?);
    assert!(third.query_lock(10..20)?);
    assert!(!third.query_lock(20..30)?);
    assert!(third.try_lock_range(20..30)?);
    first.unlock_range(10..20)?;
    assert!(!third.try_lock_range(10..20)?);
    second.unlock_range(10..20)?;
    assert!(third.try_lock_range(10..20)?);
    third.unlock_range(10..30)?;

    for invalid in [10..10, 20..10, u64::MAX..u64::MAX] {
        assert_eq!(
            first.try_lock_range(invalid).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
    }
    assert_eq!(
        first
            .try_lock_range(i64::MAX as u64..=u64::MAX)
            .unwrap_err()
            .kind(),
        io::ErrorKind::InvalidInput
    );
    let large = (1_u64 << 33)..(1_u64 << 33) + 16;
    assert!(first.try_lock_range(large.clone())?);
    assert!(!second.try_lock_range(large.clone())?);
    first.unlock_range(large)?;

    assert!(first.try_lock_range(0..)?);
    assert!(!second.try_lock_range(1_u64 << 40..)?);
    first.unlock_range(..)?;
    first.lock_shared_range(..)?;
    second.lock_shared_range(..)?;
    first.unlock_range(..)?;
    second.unlock_range(..)?;

    first.lock_range(..)?;
    let (started, waiting) = std::sync::mpsc::channel();
    let (completed, result) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        started.send(()).unwrap();
        let outcome = second.lock_range(..);
        completed.send(outcome).unwrap();
    });
    waiting.recv_timeout(Duration::from_secs(5)).unwrap();
    assert!(matches!(
        result.recv_timeout(Duration::from_millis(100)),
        Err(std::sync::mpsc::RecvTimeoutError::Timeout)
    ));
    first.unlock_range(..)?;
    result.recv_timeout(Duration::from_secs(5)).unwrap()?;
    worker.join().unwrap();
    drop(first);
    drop(third);
    fs::remove_file(path)?;
    Ok(())
}
